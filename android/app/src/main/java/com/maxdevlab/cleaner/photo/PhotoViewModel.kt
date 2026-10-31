package com.maxdevlab.cleaner.photo

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.maxdevlab.cleaner.photo.data.*
import com.maxdevlab.cleaner.photo.ads.*
import com.maxdevlab.cleaner.photo.reminders.MonthlyReminder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.maxdevlab.cleaner.photo.platform.*
import kotlinx.coroutines.CancellationException

/** UI consumes one immutable snapshot. Writes finish before the next photo is shown. */
data class PhotoState(
    val photos: List<Photo> = emptyList(), val records: Map<String, ReviewRecord> = emptyMap(),
    val access: Access = Access.NONE, val loading: Boolean = true, val busy: Boolean = false,
    val screen: String = "home", val month: String? = null, val message: String? = null,
    val canUndo: Boolean = false, val reminder: Boolean = false, val cleanupSoundsEnabled: Boolean = true,
    val showRatingInvitation: Boolean = false,
    val session: ReviewSession? = null, val trashReturn: String = "home",
    val deletedCount: Int = 0, val deletedBytes: Long = 0, val deleteReportId: String? = null,
    val deleteFailedCount: Int = 0, val deleteCanceledCount: Int = 0
) {
    val groups by lazy(LazyThreadSafetyMode.NONE) { ReviewRules.groups(photos, records) }
    // Reviewing one month does not group and sort every other month in the library.
    val currentGroup by lazy(LazyThreadSafetyMode.NONE) { ReviewRules.month(photos, records, month) }
    val remaining: List<Photo> by lazy(LazyThreadSafetyMode.NONE) {
        val available = currentGroup?.photos.orEmpty().associateBy { it.uri }
        session?.uris?.mapNotNull(available::get).orEmpty()
    }
    val marked by lazy(LazyThreadSafetyMode.NONE) { photos.filter { ReviewRules.record(it, records)?.decision == Decision.REMOVE } }
}
sealed interface PhotoEvent {
    data class CleanupReady(val id: String) : PhotoEvent
    data class WritePermission(val id: String) : PhotoEvent
    data class DeleteConsent(val id: String, val kind: DeleteAwaiting, val request: IntentSender) : PhotoEvent
}
class PhotoViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = PhotoRepository(application)
    private val ratingInvitation = RatingInvitationStore(application)
    private val soundPreferences = application.getSharedPreferences("cleanup_feedback", 0)
    private val cleanupAdSession = (application as PhotoCleanerApplication).cleanupAdSession
    private val cleanupPresentation = CleanupPresentation()
    private var foreground = false
    private val lock = Mutex()
    val platform = PhotoPlatform.current()
    // Progress is collected only by the small progress panel, not the photo grid or sorting UI.
    private val _deleteProgress = MutableStateFlow<DeleteProgress?>(null)
    val deleteProgress = _deleteProgress.asStateFlow()
    // No replay: returning to the app or rotating must never repeat a previous sound.
    private val _cleanupSounds = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val cleanupSounds = _cleanupSounds.asSharedFlow()
    private val deletion = PhotoDeleteCoordinator(androidDeletionBackend(application), DeletionJournal(application)) {
        _deleteProgress.value = it
    }
    private var activeDeleteId: String? = null
    private val _state = MutableStateFlow(PhotoState(deleteReportId = saved["deleteReportId"], showRatingInvitation = ratingInvitation.isVisible(), trashReturn = saved["trashReturn"] ?: "home", screen = (saved.get<String>("screen") ?: "home").let { if (it == "cleanupTransition") "deleted" else it }, month = saved["month"], deletedCount = saved["deletedCount"] ?: 0, deletedBytes = saved["deletedBytes"] ?: 0, deleteFailedCount = saved["deleteFailedCount"] ?: 0, deleteCanceledCount = saved["deleteCanceledCount"] ?: 0))
    val state = _state.asStateFlow()
    private val eventChannel = Channel<PhotoEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private data class Undo(val photo: Photo, val previous: ReviewRecord?)
    private val undo = ArrayDeque<Undo>()
    init {
        _state.update { it.copy(cleanupSoundsEnabled = soundPreferences.getBoolean("sound_enabled", true)) }
        refresh()
    }
    fun setCleanupSounds(enabled: Boolean) {
        soundPreferences.edit().putBoolean("sound_enabled", enabled).apply()
        _state.update { it.copy(cleanupSoundsEnabled = enabled) }
    }
    fun message(text: String) { _state.update { it.copy(message = text) } }
    fun rateClicked() {
        ratingInvitation.rateClicked()
        _state.update { it.copy(showRatingInvitation = false) }
    }
    fun clearMessage() { _state.update { it.copy(message = null) } }
    fun navigate(screen: String) {
        if (_state.value.busy) return
        val current = _state.value
        val origin = if (screen == "trash" && current.screen !in listOf("trash", "deleted")) {
            current.screen.takeIf { it in listOf("review", "finish") } ?: "home"
        } else current.trashReturn
        saved["screen"] = screen; saved["trashReturn"] = origin
        _state.update { it.copy(screen = screen, trashReturn = origin) }
    }
    fun back() {
        val state = _state.value
        val target = if (state.screen in listOf("trash", "deleted")) state.trashReturn else "home"
        navigate(if (target == "review" && state.remaining.isEmpty()) "finish" else target)
    }
    fun openMonth(id: String) {
        if (_state.value.busy) return
        val group = _state.value.groups.find { it.id == id } ?: return
        undo.clear(); saved["month"] = id; saved["screen"] = "review"
        _state.update { it.copy(month = id, screen = "review", canUndo = false,
            session = ReviewSession.start(group, it.records)) }
        saveSession()
    }
    fun openNextMonth() {
        val next = _state.value.groups.firstOrNull { !it.completed }
        if (next == null) navigate("home") else openMonth(next.id)
    }
    fun resumeReview() {
        val state = _state.value
        val group = state.currentGroup?.takeUnless { it.completed }
            ?: state.groups.firstOrNull { !it.completed } ?: state.currentGroup ?: state.groups.firstOrNull()
        if (group == null) navigate("home") else openMonth(group.id)
    }
    // Store a small bookmark, not the entire photo library, in the saved instance state.
    private fun saveSession() {
        val session = _state.value.session ?: return
        saved["reviewAnchor"] = session.uris.firstOrNull()
        saved["reviewTotal"] = session.total
        saved["reviewAll"] = session.revisiting
        saved["reviewEnded"] = session.uris.isEmpty()
    }
    private fun restoreSession(state: PhotoState): ReviewSession? {
        val group = state.currentGroup ?: return null
        val initial = ReviewSession.start(group, state.records)
        val all = saved.get<Boolean>("reviewAll") ?: initial.revisiting
        val photos = if (all) group.photos else group.photos.filter { ReviewRules.record(it, state.records)?.decision == null }
        val anchor = saved.get<String>("reviewAnchor")
        val start = photos.indexOfFirst { it.uri == anchor }.coerceAtLeast(0)
        val uris = if (saved.get<Boolean>("reviewEnded") == true) emptyList() else photos.drop(start).map { it.uri }
        return ReviewSession(uris, maxOf(saved.get<Int>("reviewTotal") ?: uris.size, uris.size), all)
    }
    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(showRatingInvitation = ratingInvitation.isVisible()) }
        lock.withLock {
            try {
                val app = getApplication<Application>()
                if (MonthlyReminder.enabled(app) && !MonthlyReminder.allowed(app)) MonthlyReminder.setEnabled(app, false)
                else MonthlyReminder.schedule(app)
                _state.update { it.copy(reminder = MonthlyReminder.enabled(app)) }
                val pending = withContext(Dispatchers.IO) { deletion.pending() }
                if (pending != null && !_state.value.busy) {
                    // A process may die after some deletions but before the final report.
                    // Reconcile durable successes immediately, without retrying any deletion.
                    val recovered = withContext(Dispatchers.IO) { deletion.recoverInterrupted() }
                    if (recovered is DeleteStep.Complete) applyDeleteReport(recovered.report)
                }
                val access = repository.access()
                if (access == Access.NONE) {
                    undo.clear()
                    _state.update { it.copy(access = access, photos = emptyList(), records = emptyMap(), loading = false, canUndo = false, screen = "home") }
                    return@withLock
                }
                val (photos, records) = repository.load()
                _state.update {
                    val next = it.copy(photos = photos, records = records, access = repository.access(), loading = false, reminder = MonthlyReminder.enabled(app))
                    val session = next.session?.reconcile(photos) ?: restoreSession(next)
                    next.copy(session = session, screen = if (next.screen == "review" && session?.uris.isNullOrEmpty()) "finish" else next.screen)
                }
                saved["screen"] = if (_state.value.screen == "cleanupTransition") "deleted" else _state.value.screen
                saveSession()
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) {
                _state.update { it.copy(access = Access.NONE, photos = emptyList(), records = emptyMap(), loading = false, message = "Photo access changed. Please choose your access again.") }
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, message = "Could not load your photos. Please try again.") }
            }
        }
    }
    private fun mutate(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _deleteProgress.value = null
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            lock.withLock {
                try { block() } catch (_: Exception) { message("Could not save this change. Please try again.") }
                finally { _state.update { it.copy(busy = false, canUndo = undo.isNotEmpty()) } }
            }
        }
    }
    fun decide(photo: Photo, decision: Decision) = mutate {
        val current = _state.value.remaining.firstOrNull()
        if (current?.uri != photo.uri || current.signature != photo.signature) return@mutate
        val old = ReviewRules.record(photo, _state.value.records)
        val next = ReviewRecord(photo.signature, decision, old?.favorite == true)
        repository.write(photo.uri, next)
        undo.addLast(Undo(photo, old))
        _state.update { it.copy(records = it.records + (photo.uri to next), session = it.session?.advance()) }
        saveSession()
        if (_state.value.remaining.isEmpty()) {
            saved["screen"] = "finish"
            _state.update { it.copy(screen = "finish", showRatingInvitation = ratingInvitation.isVisible()) }
        }
    }
    fun undo() = mutate {
        val item = undo.lastOrNull() ?: return@mutate
        repository.write(item.photo.uri, item.previous)
        undo.removeLast()
        saved["screen"] = "review"
        _state.update { it.copy(records = if (item.previous == null) it.records - item.photo.uri else it.records + (item.photo.uri to item.previous), screen = "review", session = it.session?.undo(item.photo.uri)) }
        saveSession()
    }
    fun keepSelected(uris: Set<String>) = mutate {
        val next = _state.value.records.toMutableMap()
        val changes = mutableMapOf<String, ReviewRecord>()
        for (photo in _state.value.marked.filter { it.uri in uris }) {
            val record = ReviewRecord(photo.signature, Decision.KEEP, next[photo.uri]?.favorite == true)
            changes[photo.uri] = record; next[photo.uri] = record
        }
        repository.writeAll(changes)
        undo.clear(); _state.update { it.copy(records = next, message = "Photos kept") }
    }
    fun requestDelete(selected: Set<String>) {
        if (_state.value.busy) return
        _deleteProgress.value = DeleteProgress()
        _state.update { it.copy(busy = true) }
        runDeletion {
            // Reconcile durable successes from an interrupted operation before starting a new one.
            val recovered = withContext(Dispatchers.IO) { deletion.recoverInterrupted() }
            if (recovered is DeleteStep.Complete) applyDeleteReport(recovered.report)
            val candidates = ReviewRules.deleteCandidates(_state.value.photos, _state.value.records, selected)
            withContext(Dispatchers.IO) { deletion.start(candidates) }
        }
    }
    fun deleteResult(id: String, kind: DeleteAwaiting, approved: Boolean) {
        if (activeDeleteId != null && activeDeleteId != id) return
        runDeletion { withContext(Dispatchers.IO) { deletion.reply(id, kind, approved) } }
    }
    fun deleteLaunchFailed(id: String) {
        if (activeDeleteId != null && activeDeleteId != id) return
        runDeletion { withContext(Dispatchers.IO) { deletion.launchFailed(id) } }
    }
    private fun runDeletion(action: suspend () -> DeleteStep<IntentSender>) = viewModelScope.launch {
        var refreshAfter = false
        lock.withLock {
            _state.update { it.copy(busy = true) }
            try {
                when (val step = action()) {
                    DeleteStep.Idle -> { activeDeleteId = null; _deleteProgress.value = null; _state.update { it.copy(busy = false) } }
                    is DeleteStep.Permission -> {
                        activeDeleteId = step.id
                        eventChannel.send(PhotoEvent.WritePermission(step.id))
                    }
                    is DeleteStep.Consent -> {
                        activeDeleteId = step.id
                        eventChannel.send(PhotoEvent.DeleteConsent(step.id, step.kind, step.request))
                    }
                    is DeleteStep.Complete -> {
                        applyDeleteReport(step.report, deferResult = true)
                        activeDeleteId = null
                        _deleteProgress.value = null
                        val eligible = cleanupAdSession.record(step.report)
                        if (step.report.deleted.isNotEmpty()) {
                            cleanupPresentation.stage(step.report.id, eligible && foreground, CleanupSoundRules.completed(step.report))
                            eventChannel.send(PhotoEvent.CleanupReady(step.report.id))
                        } else _state.update { it.copy(busy = false) }
                        refreshAfter = true
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // The journal retains per-photo receipts; a later refresh/retry reconciles them.
                activeDeleteId = null
                _deleteProgress.value = null
                _state.update { it.copy(busy = false, screen = if (it.screen == "cleanupTransition") "deleted" else it.screen, message = "Cleanup was interrupted. Refresh and review the remaining photos before trying again.") }
                refreshAfter = true
            }
        }
        if (refreshAfter) refresh()
    }
    private suspend fun applyDeleteReport(report: DeleteReport, deferResult: Boolean = false) {
        val deleted = report.deleted.map { PhotoIdentity(it.uri, it.signature) }.toSet()
        repository.applyDeleteReport(report)
        undo.clear()
        val bytes = report.deleted.sumOf { it.bytes }
        val screen = if (deleted.isNotEmpty()) "deleted" else _state.value.screen
        saved["deleteReportId"] = report.id
        saved["screen"] = screen
        saved["deletedCount"] = deleted.size; saved["deletedBytes"] = bytes
        saved["deleteFailedCount"] = report.failed.size; saved["deleteCanceledCount"] = report.canceled.size
        val notice = when {
            report.interrupted -> "Previous cleanup interrupted. Only confirmed deletions are counted; review the remaining photos."
            report.failed.isNotEmpty() -> "${report.failed.size} photos could not be deleted. Review them and try again."
            report.canceled.isNotEmpty() && deleted.isEmpty() -> "Deletion canceled. Your photos were kept."
            report.canceled.isNotEmpty() -> "Deletion stopped. Photos already deleted cannot be restored by this app."
            else -> null
        }
        _state.update {
            val library = LibrarySnapshot(it.photos, it.records).excluding(deleted)
            it.copy(photos = library.photos, records = library.records,
            deletedCount = deleted.size, deletedBytes = bytes, deleteReportId = report.id,
            showRatingInvitation = if (deleted.isNotEmpty()) ratingInvitation.isVisible() else it.showRatingInvitation,
            deleteFailedCount = report.failed.size,
            deleteCanceledCount = report.canceled.size, screen = if (deferResult && deleted.isNotEmpty()) "cleanupTransition" else screen, canUndo = false, message = notice, session = it.session?.reconcile(library.photos)) }
        saveSession()
        withContext(Dispatchers.IO) { deletion.acknowledge(report.id) }
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) cleanupPresentation.backgrounded()
    }
    fun claimCleanupAd(id: String): Boolean = _state.value.let {
        it.screen == "cleanupTransition" && it.deleteReportId == id && foreground && cleanupPresentation.claimAd(id)
    }
    fun finishCleanupPresentation(id: String) {
        if (_state.value.screen != "cleanupTransition" || _state.value.deleteReportId != id) return
        saved["screen"] = "deleted"
        _state.update { it.copy(screen = "deleted", busy = false) }
    }
    fun cleanupResultVisible(id: String) {
        if (foreground && _state.value.screen == "deleted" && _state.value.deleteReportId == id && cleanupPresentation.takeSound(id)) {
            _cleanupSounds.tryEmit(Unit)
        }
    }
    fun setReminder(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled && !MonthlyReminder.allowed(app)) { message("Allow notifications to enable the monthly reminder."); return }
        MonthlyReminder.setEnabled(app, enabled)
        _state.update { it.copy(reminder = enabled) }
    }
    fun resetProgress() = mutate {
        repository.reset(); undo.clear(); saved["screen"] = "home"
        listOf("reviewAnchor", "reviewTotal", "reviewAll", "reviewEnded").forEach { saved.remove<Any>(it) }
        _state.update { it.copy(records = emptyMap(), session = null, screen = "home", message = "Review progress cleared. Your photos were not changed.") }
    }
}
