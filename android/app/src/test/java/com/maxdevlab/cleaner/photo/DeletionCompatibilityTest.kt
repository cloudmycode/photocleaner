package com.maxdevlab.cleaner.photo

import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.platform.PhotoPlatform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeletionCompatibilityTest {
    private val photos = (1..3).map { Photo("content://media/external/images/media/$it", "$it.jpg", 1000, it * 100L, 100, 100, 1) }
    private class MemoryStore : DeletionStore {
        var session: DeleteSession? = null
        override fun load() = session
        override fun create(session: DeleteSession) { this.session = session }
        override fun saveHeader(session: DeleteSession) { this.session = session.copy(items = this.session?.items ?: session.items) }
        override fun saveItem(item: DeleteItem) { session = session!!.copy(items = session!!.items.map { if (it.uri == item.uri) item else it }) }
        override fun clear() { session = null }
    }
    private open class Backend(override val mode: DeleteMode) : DeletionBackend<String> {
        var needsPermission = false
        val stale = mutableSetOf<String>()
        val answers = mutableMapOf<String, ArrayDeque<OneDelete<String>>>()
        val calls = mutableListOf<String>()
        var batchSize = 0
        override fun needsWritePermission() = needsPermission
        override fun isCurrent(item: DeleteItem) = item.uri !in stale
        override fun deleteOne(item: DeleteItem): OneDelete<String> { calls += item.uri; return answers[item.uri]?.removeFirstOrNull() ?: OneDelete.Deleted }
        override fun batchConsent(items: List<DeleteItem>): String { batchSize = items.size; return "batch" }
    }
    @Test fun twoThousandCandidatesUseOneBulkValidationAndExcludeChangedPhotos() = runBlocking {
        var validations = 0
        val backend = object : Backend(DeleteMode.BATCH) {
            override fun currentUris(items: List<DeleteItem>): Set<String> {
                validations++
                return items.dropLast(1).map { it.uri }.toSet()
            }
        }
        val many = (1..2000).map { photos.first().copy(uri = "content://media/external/images/media/$it") }
        val store = MemoryStore()
        val engine = PhotoDeleteCoordinator(backend, store)
        val prompt = engine.start(many) as DeleteStep.Consent
        assertEquals(1, validations)
        assertEquals(1999, backend.batchSize)
        val report = (engine.reply(prompt.id, prompt.kind, true) as DeleteStep.Complete).report
        assertEquals(1999, report.deleted.size)
        assertEquals(many.last().uri, report.failed.single().uri)
    }
    @Test fun platformBoundariesAreCentralized() {
        assertEquals(DeleteMode.LEGACY, PhotoPlatform(27).deleteMode)
        assertEquals(DeleteMode.LEGACY, PhotoPlatform(28).deleteMode)
        assertEquals(DeleteMode.SCOPED, PhotoPlatform(29).deleteMode)
        assertEquals(DeleteMode.BATCH, PhotoPlatform(30).deleteMode)
        assertFalse(PhotoPlatform(27).supportsVolumes); assertTrue(PhotoPlatform(29).supportsVolumes)
        assertFalse(PhotoPlatform(29).supportsTrash); assertTrue(PhotoPlatform(30).supportsTrash)
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), PhotoPlatform(32).readPermissions.toList())
        assertEquals(listOf("android.permission.READ_MEDIA_IMAGES"), PhotoPlatform(33).readPermissions.toList())
        assertEquals(2, PhotoPlatform(34).readPermissions.size)
    }
    @Test fun legacyReportsActualSuccessAndFailurePerPhoto() = runBlocking {
        val backend = Backend(DeleteMode.LEGACY)
        backend.answers[photos[1].uri] = ArrayDeque(listOf(OneDelete.Failed))
        val result = PhotoDeleteCoordinator(backend, MemoryStore()).start(photos) as DeleteStep.Complete
        assertEquals(listOf(photos[0].uri, photos[2].uri), result.report.deleted.map { it.uri })
        assertEquals(listOf(photos[1].uri), result.report.failed.map { it.uri })
    }
    @Test fun legacyPermissionDenialNeverDeletesAnything() = runBlocking {
        val backend = Backend(DeleteMode.LEGACY).apply { needsPermission = true }
        val engine = PhotoDeleteCoordinator(backend, MemoryStore())
        val prompt = engine.start(photos) as DeleteStep.Permission
        val result = engine.reply(prompt.id, DeleteAwaiting.WRITE_PERMISSION, false) as DeleteStep.Complete
        assertTrue(backend.calls.isEmpty()); assertEquals(3, result.report.canceled.size)
    }
    @Test fun legacyPermissionGrantIsRecheckedBeforeDeletion() = runBlocking {
        val backend = Backend(DeleteMode.LEGACY).apply { needsPermission = true }
        val engine = PhotoDeleteCoordinator(backend, MemoryStore())
        val prompt = engine.start(photos) as DeleteStep.Permission
        val result = engine.reply(prompt.id, DeleteAwaiting.WRITE_PERMISSION, true) as DeleteStep.Complete
        assertTrue(backend.calls.isEmpty()); assertEquals(3, result.report.canceled.size)
    }
    @Test fun legacyGrantedPermissionContinuesTheSameSelectedQueue() = runBlocking {
        val backend = Backend(DeleteMode.LEGACY).apply { needsPermission = true }
        val engine = PhotoDeleteCoordinator(backend, MemoryStore())
        val prompt = engine.start(photos.take(1)) as DeleteStep.Permission
        backend.needsPermission = false
        val result = engine.reply(prompt.id, DeleteAwaiting.WRITE_PERMISSION, true) as DeleteStep.Complete
        assertEquals(listOf(photos[0].uri), backend.calls)
        assertEquals(1, result.report.deleted.size)
    }
    @Test fun android10GrantRetriesTheActualDeleteAndCanResumeAfterRecreation() = runBlocking {
        val backend = Backend(DeleteMode.SCOPED)
        backend.answers[photos[0].uri] = ArrayDeque(listOf(OneDelete.NeedsConsent("one"), OneDelete.Deleted))
        val store = MemoryStore()
        val first = PhotoDeleteCoordinator(backend, store).start(photos) as DeleteStep.Consent
        assertEquals(0, store.load()!!.items.count { it.status == DeleteStatus.DELETED })
        val recreated = PhotoDeleteCoordinator(backend, store)
        val result = recreated.reply(first.id, first.kind, true) as DeleteStep.Complete
        assertEquals(3, result.report.deleted.size); assertEquals(2, backend.calls.count { it == photos[0].uri })
    }
    @Test fun android10CancelPreservesEarlierSuccessAndStopsRemainingQueue() = runBlocking {
        val backend = Backend(DeleteMode.SCOPED)
        backend.answers[photos[1].uri] = ArrayDeque(listOf(OneDelete.NeedsConsent("two")))
        val engine = PhotoDeleteCoordinator(backend, MemoryStore())
        val prompt = engine.start(photos) as DeleteStep.Consent
        val result = engine.reply(prompt.id, prompt.kind, false) as DeleteStep.Complete
        assertEquals(listOf(photos[0].uri), result.report.deleted.map { it.uri })
        assertEquals(2, result.report.canceled.size); assertFalse(backend.calls.contains(photos[2].uri))
    }
    @Test fun android10RepeatedConsentDoesNotLoopOrClaimSuccess() = runBlocking {
        val backend = Backend(DeleteMode.SCOPED)
        backend.answers[photos[0].uri] = ArrayDeque(listOf(OneDelete.NeedsConsent("one"), OneDelete.NeedsConsent("again")))
        val engine = PhotoDeleteCoordinator(backend, MemoryStore())
        val prompt = engine.start(photos) as DeleteStep.Consent
        val result = engine.reply(prompt.id, prompt.kind, true) as DeleteStep.Complete
        assertEquals(1, result.report.failed.size); assertEquals(2, result.report.deleted.size)
    }
    @Test fun stalePhotosNeverReachTheDeleteBackend() = runBlocking {
        val backend = Backend(DeleteMode.LEGACY).apply { stale += photos[0].uri }
        val result = PhotoDeleteCoordinator(backend, MemoryStore()).start(photos) as DeleteStep.Complete
        assertEquals(1, result.report.failed.size); assertFalse(photos[0].uri in backend.calls)
    }
    @Test fun batchOnlyCountsSuccessAfterSystemResult() = runBlocking {
        val backend = Backend(DeleteMode.BATCH).apply { stale += photos[2].uri }
        val store = MemoryStore(); val engine = PhotoDeleteCoordinator(backend, store)
        val prompt = engine.start(photos) as DeleteStep.Consent
        assertEquals(2, backend.batchSize); assertTrue(backend.calls.isEmpty())
        assertEquals(0, store.load()!!.items.count { it.status == DeleteStatus.DELETED })
        val result = engine.reply(prompt.id, prompt.kind, true) as DeleteStep.Complete
        assertEquals(2, result.report.deleted.size); assertEquals(1, result.report.failed.size)
    }
    @Test fun batchCancelPreservesAllPhotos() = runBlocking {
        val engine = PhotoDeleteCoordinator(Backend(DeleteMode.BATCH), MemoryStore())
        val prompt = engine.start(photos) as DeleteStep.Consent
        val result = engine.reply(prompt.id, prompt.kind, false) as DeleteStep.Complete
        assertTrue(result.report.deleted.isEmpty()); assertEquals(3, result.report.canceled.size)
    }
    @Test fun oldOrWrongCallbacksCannotApproveANewOperation() = runBlocking {
        val backend = Backend(DeleteMode.BATCH); val engine = PhotoDeleteCoordinator(backend, MemoryStore())
        val prompt = engine.start(photos) as DeleteStep.Consent
        assertEquals(DeleteStep.Idle, engine.reply("old-operation", prompt.kind, true))
        assertEquals(DeleteStep.Idle, engine.reply(prompt.id, DeleteAwaiting.ITEM_CONSENT, true))
        assertEquals(DeleteAwaiting.BATCH_CONSENT, engine.pending()!!.awaiting)
    }
    @Test fun interruptedInFlightOperationIsNeverAssumedDeletedOrRetried() {
        val store = MemoryStore().apply { create(DeleteSession("saved", DeleteMode.LEGACY, photos.mapIndexed { i, p -> DeleteItem(p.uri, p.signature, p.bytes, if (i == 0) DeleteStatus.DELETED else if (i == 1) DeleteStatus.IN_FLIGHT else DeleteStatus.PENDING) })) }
        val backend = Backend(DeleteMode.LEGACY); val engine = PhotoDeleteCoordinator(backend, store)
        val result = engine.recoverInterrupted() as DeleteStep.Complete
        assertEquals(1, result.report.deleted.size); assertEquals(2, result.report.failed.size)
        assertTrue(result.report.interrupted); assertTrue(backend.calls.isEmpty())
        engine.acknowledge("wrong"); assertNotNull(store.load())
        engine.acknowledge("saved"); assertNull(store.load())
    }
    @Test fun launchFailureDoesNotDelete() = runBlocking {
        val engine = PhotoDeleteCoordinator(Backend(DeleteMode.BATCH), MemoryStore())
        val prompt = engine.start(photos) as DeleteStep.Consent
        val result = engine.launchFailed(prompt.id) as DeleteStep.Complete
        assertEquals(3, result.report.failed.size); assertTrue(result.report.deleted.isEmpty())
    }
    @Test fun progressFollowsPersistedResultsAndNeverCountsFailedBytes() = runBlocking {
        val backend = Backend(DeleteMode.LEGACY).apply { answers[photos[1].uri] = ArrayDeque(listOf(OneDelete.Failed)) }
        val store = MemoryStore()
        val updates = mutableListOf<DeleteProgress>()
        PhotoDeleteCoordinator(backend, store) { progress ->
            updates += progress
            if (progress.phase != DeletePhase.PREPARING) {
                assertEquals(DeleteProgress.from(store.load()!!, progress.phase), progress)
            }
        }.start(photos)
        assertEquals(listOf(0, 1, 2, 3), updates.map { it.processed }.distinct())
        assertEquals(DeleteProgress(DeletePhase.FINISHING, 3, 3, 2, 1, 400), updates.last())
        assertEquals(100L, updates.first { it.processed == 2 }.freedBytes)
    }
    @Test fun systemConsentDoesNotSimulateDeletionProgress() = runBlocking {
        val updates = mutableListOf<DeleteProgress>()
        val engine = PhotoDeleteCoordinator(Backend(DeleteMode.BATCH), MemoryStore(), updates::add)
        val prompt = engine.start(photos) as DeleteStep.Consent
        assertEquals(DeletePhase.WAITING_FOR_SYSTEM, updates.last().phase)
        assertTrue(updates.all { it.processed == 0 && it.freedBytes == 0L })
        engine.reply(prompt.id, prompt.kind, false)
        assertEquals(3, updates.last().processed)
        assertEquals(0, updates.last().deleted)
        assertEquals(0L, updates.last().freedBytes)
    }
    @Test fun scopedConsentRetainsEarlierProgressAndContinuesWithoutDoubleCounting() = runBlocking {
        val backend = Backend(DeleteMode.SCOPED).apply {
            answers[photos[1].uri] = ArrayDeque(listOf(OneDelete.NeedsConsent("one"), OneDelete.Deleted))
        }
        val updates = mutableListOf<DeleteProgress>()
        val engine = PhotoDeleteCoordinator(backend, MemoryStore(), updates::add)
        val prompt = engine.start(photos) as DeleteStep.Consent
        assertEquals(DeleteProgress(DeletePhase.WAITING_FOR_SYSTEM, 3, 1, 1, 0, 100), updates.last())
        engine.reply(prompt.id, prompt.kind, true)
        assertEquals(listOf(0, 1, 2, 3), updates.map { it.processed }.distinct())
        assertEquals(600L, updates.last().freedBytes)
    }
}
