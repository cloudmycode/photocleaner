package com.maxdevlab.cleaner.photo.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

enum class DeleteMode { LEGACY, SCOPED, BATCH }
enum class DeleteStatus { PENDING, IN_FLIGHT, DELETED, FAILED, CANCELED }
enum class DeleteAwaiting { NONE, WRITE_PERMISSION, ITEM_CONSENT, BATCH_CONSENT, COMPLETE }
data class DeleteItem(val uri: String, val signature: String, val bytes: Long, val status: DeleteStatus = DeleteStatus.PENDING, val consentUsed: Boolean = false)
data class DeleteSession(val id: String, val mode: DeleteMode, val items: List<DeleteItem>, val awaiting: DeleteAwaiting = DeleteAwaiting.NONE, val interrupted: Boolean = false)
data class DeleteReport(val id: String, val items: List<DeleteItem>, val interrupted: Boolean) {
    val deleted get() = items.filter { it.status == DeleteStatus.DELETED }
    val failed get() = items.filter { it.status == DeleteStatus.FAILED }
    val canceled get() = items.filter { it.status == DeleteStatus.CANCELED }
}
sealed interface OneDelete<out C> {
    data object Deleted : OneDelete<Nothing>
    data object Failed : OneDelete<Nothing>
    data class NeedsConsent<C>(val request: C) : OneDelete<C>
}
interface DeletionBackend<C> {
    val mode: DeleteMode
    fun needsWritePermission(): Boolean
    fun isCurrent(item: DeleteItem): Boolean
    fun currentUris(items: List<DeleteItem>): Set<String> = items.filter(::isCurrent).map { it.uri }.toSet()
    fun deleteOne(item: DeleteItem): OneDelete<C>
    fun batchConsent(items: List<DeleteItem>): C
}
interface DeletionStore {
    fun load(): DeleteSession?
    fun create(session: DeleteSession)
    fun saveHeader(session: DeleteSession)
    fun saveItem(item: DeleteItem)
    fun saveItems(items: List<DeleteItem>) { items.forEach(::saveItem) }
    fun clear()
}
sealed interface DeleteStep<out C> {
    data object Idle : DeleteStep<Nothing>
    data class Permission(val id: String) : DeleteStep<Nothing>
    data class Consent<C>(val id: String, val kind: DeleteAwaiting, val request: C) : DeleteStep<C>
    data class Complete(val report: DeleteReport) : DeleteStep<Nothing>
}

/** Shared, platform-independent queue. Caller serializes calls and supplies an IO dispatcher.
 * A grant is never counted as a deletion. Only an actual delete result (or completed system
 * batch) can enter DELETED. Interrupted work never resumes deletion automatically.
 */
class PhotoDeleteCoordinator<C>(private val backend: DeletionBackend<C>, private val store: DeletionStore,
    private val onProgress: (DeleteProgress) -> Unit = {}) {
    fun pending() = store.load()
    suspend fun start(photos: List<Photo>): DeleteStep<C> {
        check(store.load() == null) { "Previous deletion must be reconciled first" }
        if (photos.isEmpty()) return DeleteStep.Idle
        val session = DeleteSession(UUID.randomUUID().toString(), backend.mode,
            photos.distinctBy { it.uri }.take(ReviewRules.DELETE_LIMIT).map { DeleteItem(it.uri, it.signature, it.bytes) })
        publish(session, DeletePhase.PREPARING)
        store.create(session)
        if (backend.needsWritePermission()) {
            store.saveHeader(session.copy(awaiting = DeleteAwaiting.WRITE_PERMISSION))
            publish(session, DeletePhase.WAITING_FOR_SYSTEM)
            return DeleteStep.Permission(session.id)
        }
        return advance(session)
    }
    suspend fun reply(id: String, kind: DeleteAwaiting, approved: Boolean): DeleteStep<C> {
        var session = store.load() ?: return DeleteStep.Idle
        if (session.id != id || session.awaiting != kind || kind !in listOf(DeleteAwaiting.WRITE_PERMISSION, DeleteAwaiting.ITEM_CONSENT, DeleteAwaiting.BATCH_CONSENT)) return DeleteStep.Idle
        if (!approved || (kind == DeleteAwaiting.WRITE_PERMISSION && backend.needsWritePermission())) {
            session = settlePending(session, DeleteStatus.CANCELED)
            return complete(session)
        }
        if (kind == DeleteAwaiting.BATCH_CONSENT) return complete(settlePending(session, DeleteStatus.DELETED))
        session = session.copy(awaiting = DeleteAwaiting.NONE)
        store.saveHeader(session)
        return advance(session)
    }
    fun launchFailed(id: String): DeleteStep<C> {
        val session = store.load()?.takeIf { it.id == id } ?: return DeleteStep.Idle
        return complete(settlePending(session, DeleteStatus.FAILED))
    }
    fun recoverInterrupted(): DeleteStep<C> {
        val session = store.load() ?: return DeleteStep.Idle
        if (session.awaiting == DeleteAwaiting.COMPLETE) return complete(session)
        return complete(settlePending(session.copy(interrupted = true), DeleteStatus.FAILED))
    }
    fun acknowledge(id: String) { if (store.load()?.let { it.id == id && it.awaiting == DeleteAwaiting.COMPLETE } == true) store.clear() }

    private suspend fun advance(initial: DeleteSession): DeleteStep<C> {
        var session = initial
        publish(session, if (backend.mode == DeleteMode.BATCH) DeletePhase.PREPARING else DeletePhase.DELETING)
        if (backend.mode == DeleteMode.BATCH) {
            currentCoroutineContext().ensureActive()
            val candidates = session.items.filter { it.status == DeleteStatus.PENDING }
            val current = runCatching { backend.currentUris(candidates) }.getOrDefault(emptySet())
            val invalid = candidates.filter { it.uri !in current }.map { it.copy(status = DeleteStatus.FAILED) }
            store.saveItems(invalid)
            val invalidUris = invalid.map { it.uri }.toSet()
            session = session.copy(items = session.items.map { if (it.uri in invalidUris) it.copy(status = DeleteStatus.FAILED) else it })
            val pending = session.items.filter { it.status == DeleteStatus.PENDING }
            if (pending.isEmpty()) return complete(session)
            val request = try { backend.batchConsent(pending) } catch (_: Exception) { return complete(settlePending(session, DeleteStatus.FAILED)) }
            store.saveHeader(session.copy(awaiting = DeleteAwaiting.BATCH_CONSENT))
            publish(session, DeletePhase.WAITING_FOR_SYSTEM)
            return DeleteStep.Consent(session.id, DeleteAwaiting.BATCH_CONSENT, request)
        }
        for (original in session.items) {
            currentCoroutineContext().ensureActive()
            if (original.status != DeleteStatus.PENDING) continue
            if (!runCatching { backend.isCurrent(original) }.getOrDefault(false)) {
                session = update(session, original.copy(status = DeleteStatus.FAILED)); continue
            }
            // Persist before the non-transactional provider operation. A crash here is uncertain,
            // never counted as success and never automatically retried after process recreation.
            session = update(session, original.copy(status = DeleteStatus.IN_FLIGHT))
            val result = try { backend.deleteOne(original) } catch (_: Exception) { OneDelete.Failed }
            when (result) {
                OneDelete.Deleted -> session = update(session, original.copy(status = DeleteStatus.DELETED))
                OneDelete.Failed -> session = update(session, original.copy(status = DeleteStatus.FAILED))
                is OneDelete.NeedsConsent -> {
                    if (original.consentUsed) session = update(session, original.copy(status = DeleteStatus.FAILED))
                    else {
                        session = update(session, original.copy(status = DeleteStatus.PENDING, consentUsed = true))
                        store.saveHeader(session.copy(awaiting = DeleteAwaiting.ITEM_CONSENT))
                        publish(session, DeletePhase.WAITING_FOR_SYSTEM)
                        return DeleteStep.Consent(session.id, DeleteAwaiting.ITEM_CONSENT, result.request)
                    }
                }
            }
        }
        return complete(session)
    }
    private fun update(session: DeleteSession, item: DeleteItem): DeleteSession {
        store.saveItem(item)
        val next = session.copy(items = session.items.map { if (it.uri == item.uri) item else it })
        if (item.status == DeleteStatus.DELETED || item.status == DeleteStatus.FAILED) publish(next, DeletePhase.DELETING)
        return next
    }
    private fun settlePending(initial: DeleteSession, status: DeleteStatus): DeleteSession {
        val changed = initial.items.filter { it.status == DeleteStatus.PENDING || it.status == DeleteStatus.IN_FLIGHT }.map { it.copy(status = status) }
        store.saveItems(changed)
        val uris = changed.map { it.uri }.toSet()
        return initial.copy(items = initial.items.map { if (it.uri in uris) it.copy(status = status) else it })
    }
    private fun complete(session: DeleteSession): DeleteStep.Complete {
        store.saveHeader(session.copy(awaiting = DeleteAwaiting.COMPLETE))
        publish(session, DeletePhase.FINISHING)
        return DeleteStep.Complete(DeleteReport(session.id, session.items, session.interrupted))
    }
    private fun publish(session: DeleteSession, phase: DeletePhase) { onProgress(DeleteProgress.from(session, phase)) }
}
