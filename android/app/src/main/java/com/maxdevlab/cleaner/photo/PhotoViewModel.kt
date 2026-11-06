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
    val screen: String = "smart", val month: String? = null, val message: String? = null,
    val canUndo: Boolean = false, val reminder: Boolean = false, val cleanupSoundsEnabled: Boolean = true,
    val showRatingInvitation: Boolean = false,
    val session: ReviewSession? = null, val trashReturn: String = "home",
    val deletedVideoCount: Int = 0, val deletedCount: Int = 0, val deletedBytes: Long = 0, val deleteReportId: String? = null,
    val deleteFailedCount: Int = 0, val deleteCanceledCount: Int = 0,
    val reviewSource: String = "home", val cleanupGroups: List<CleanupGroup> = emptyList(),
    val scanning: Boolean = false, val scanned: Int = 0, val scanTotal: Int = 0, val scanComplete: Boolean = false,
    val mediaFilter: MediaFilter = MediaFilter.ALL,
    val scanSkipped: Int = 0, val scanError: String? = null
) {
    val groups by lazy(LazyThreadSafetyMode.NONE) { ReviewRules.groups(photos.filter { mediaFilter.includes(it) }, records) }
    // Reviewing one month does not group and sort every other month in the library.
    val screenshots by lazy(LazyThreadSafetyMode.NONE) { photos.filter { it.isScreenshot } }
    val reviewPhotos get() = if (reviewSource == "screenshots") screenshots else photos.filter { mediaFilter.includes(it) }
    val reviewGroups get() = if (reviewSource == "screenshots") ReviewRules.groups(screenshots, records) else groups
    val currentGroup by lazy(LazyThreadSafetyMode.NONE) {
        if (reviewSource == "screenshots" && month == null) screenshots.takeIf { it.isNotEmpty() }?.let {
            MonthGroup(it.first().month, it, it.count { p -> ReviewRules.record(p, records)?.decision != null })
        } else ReviewRules.month(reviewPhotos, records, month)
    }
    val reviewTitle get() = if (reviewSource == "screenshots") "Screenshots" else currentGroup?.title.orEmpty()
    fun cleanup(kind: CleanupKind) = cleanupGroups.filter { it.kind == kind }
    fun cleanupCandidates(selected: Set<String>): List<Photo> = cleanupGroups
        .filter { it.kind == if (screen == "duplicates") CleanupKind.DUPLICATE else CleanupKind.BURST }
        .flatMap { it.photos }.distinctBy { it.uri }.filter { it.uri in selected }.take(ReviewRules.DELETE_LIMIT)
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
    private val analyzer = SmartPhotoAnalyzer(application)
    internal var cleanupSelection = CleanupSelection()
    private var scanJob: kotlinx.coroutines.Job? = null
    @Volatile private var scanGeneration = 0
    private var analyzedPhotos: List<Photo>? = null
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
    private val _state = MutableStateFlow(PhotoState(deletedVideoCount = saved["deletedVideoCount"] ?: 0, mediaFilter = saved.get<String>("mediaFilter")?.let { MediaFilter.valueOf(it) } ?: MediaFilter.ALL, reviewSource = saved["reviewSource"] ?: "home", deleteReportId = saved["deleteReportId"], showRatingInvitation = ratingInvitation.isVisible(), trashReturn = saved["trashReturn"] ?: "home", screen = (saved.get<String>("screen") ?: "smart").let { if (it == "cleanupTransition") "deleted" else it }, month = saved["month"], deletedCount = saved["deletedCount"] ?: 0, deletedBytes = saved["deletedBytes"] ?: 0, deleteFailedCount = saved["deleteFailedCount"] ?: 0, deleteCanceledCount = saved["deleteCanceledCount"] ?: 0))
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
            current.screen.takeIf { it in listOf("review", "finish", "screenshots", "smart", "duplicates", "bursts") } ?: "home"
        } else current.trashReturn
        saved["screen"] = screen; saved["trashReturn"] = origin
        _state.update { it.copy(screen = screen, trashReturn = origin) }
        if (screen in listOf("smart", "duplicates", "bursts")) scanCleanup()
    }
    fun back() {
        val state = _state.value
        val target = when (state.screen) {
            "trash", "deleted" -> state.trashReturn
            "review", "finish" -> state.reviewSource
            "duplicates", "bursts" -> "smart"
            else -> "smart"
        }
        navigate(if (target == "review" && state.remaining.isEmpty()) "finish" else target)
    }
    fun setMediaFilter(filter: MediaFilter) {
        if (_state.value.busy) return
        saved["mediaFilter"] = filter.name
        undo.clear()
        _state.update { it.copy(mediaFilter = filter, session = null, canUndo = false) }
        listOf("reviewAnchor", "reviewTotal", "reviewAll", "reviewEnded").forEach { saved.remove<Any>(it) }
    }
    val videoPositions = mutableMapOf<String, Int>()
    fun openMonth(id: String, source: String = "home") {
        if (_state.value.busy) return
        val group = (if (source == "screenshots") ReviewRules.groups(_state.value.screenshots, _state.value.records) else _state.value.groups).find { it.id == id } ?: return
        undo.clear(); saved["month"] = id; saved["screen"] = "review"; saved["reviewSource"] = source
        _state.update { it.copy(month = id, reviewSource = source, screen = "review", canUndo = false,
            session = ReviewSession.start(group, it.records)) }
        saveSession()
    }
    fun openNextMonth() {
        val next = _state.value.reviewGroups.firstOrNull { !it.completed }
        if (next == null) navigate(_state.value.reviewSource) else openMonth(next.id, _state.value.reviewSource)
    }
    fun resumeReview() {
        val state = _state.value
        val group = state.currentGroup?.takeUnless { it.completed }
            ?: state.reviewGroups.firstOrNull { !it.completed } ?: state.currentGroup ?: state.reviewGroups.firstOrNull()
        if (group == null) navigate(state.reviewSource) else if (state.reviewSource == "screenshots" && state.month == null) openScreenshots() else openMonth(group.id, state.reviewSource)
    }
    fun openScreenshots() {
        if (_state.value.busy) return
        val photos = _state.value.screenshots
        if (photos.isEmpty()) return
        undo.clear(); saved["month"] = null; saved["screen"] = "review"; saved["reviewSource"] = "screenshots"
        _state.update { it.copy(month = null, reviewSource = "screenshots", screen = "review", canUndo = false,
            session = ReviewSession.start(MonthGroup(photos.first().month, photos, 0), it.records)) }
        saveSession()
    }
    fun scanCleanup(force: Boolean = false) {
        val snapshot = _state.value
        if (snapshot.access == Access.NONE || snapshot.loading || snapshot.busy) return
        val images = snapshot.photos.filterNot { it.isVideo }
        if (!force && analyzedPhotos == images && (snapshot.scanComplete || scanJob?.isActive == true)) return
        scanJob?.cancel()
        val generation = ++scanGeneration
        analyzedPhotos = images
        _state.update { it.copy(scanning = true, scanComplete = false, scanned = 0, scanTotal = 0, scanSkipped = 0, scanError = null) }
        scanJob = viewModelScope.launch {
            try {
                var cachedCount: Int? = null
                val result = analyzer.analyze(images, snapshot.records) { progress ->
                    val cached = cachedCount ?: progress.scanned.also { cachedCount = it }
                    if (generation == scanGeneration) _state.update {
                        it.copy(scanned = progress.scanned - cached, scanTotal = images.size - cached, cleanupGroups = progress.groups?.let { groups ->
                            mergeCleanupGroups(groups, it.cleanupGroups)
                        } ?: it.cleanupGroups)
                    }
                }
                if (generation == scanGeneration) _state.update {
                    // Preserve processed one-photo groups during this visit, as in iOS.
                    it.copy(cleanupGroups = mergeCleanupGroups(result.groups, it.cleanupGroups), scanning = false, scanComplete = true, scanSkipped = result.skipped)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (generation == scanGeneration) _state.update { it.copy(scanning = false, scanError = "Could not finish scanning. Please try again.") } }
        }
    }
    private fun mergeCleanupGroups(fresh: List<CleanupGroup>, previous: List<CleanupGroup>): List<CleanupGroup> {
        val included = fresh.flatMap { group -> group.photos.map { group.kind to it.uri } }.toHashSet()
        return fresh + previous.filter { it.photos.size == 1 && (it.kind to it.photos.first().uri) !in included }
    }
    private fun invalidateCleanup(photos: List<Photo>) {
        if (analyzedPhotos == photos.filterNot { it.isVideo }) return
        scanJob?.cancel(); scanGeneration++; analyzedPhotos = null
        val identities = photos.associateBy { PhotoIdentity(it.uri, it.signature) }
        _state.update { it.copy(cleanupGroups = it.cleanupGroups.mapNotNull { g -> g.reconcile(identities) }, scanning = false, scanComplete = false) }
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
        val uris = if (saved.get<Boolean>("reviewEnded") == true) {
            group.photos.filter { ReviewRules.record(it, state.records)?.decision == null }.map { it.uri }
        } else if (all) photos.drop(start).map { it.uri }
        else (photos.drop(start) + photos.take(start)).map { it.uri }
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
                    undo.clear(); invalidateCleanup(emptyList())
                    _state.update { it.copy(access = access, photos = emptyList(), records = emptyMap(), loading = false, canUndo = false, screen = "smart") }
                    return@withLock
                }
                val (photos, records) = repository.load()
                invalidateCleanup(photos)
                _state.update {
                    val next = it.copy(photos = photos, records = records, access = repository.access(), loading = false, reminder = MonthlyReminder.enabled(app))
                    val session = next.session?.reconcile(next.currentGroup?.photos.orEmpty(), records) ?: restoreSession(next)
                    val screen = when {
                        next.screen == "review" && session?.uris.isNullOrEmpty() -> "finish"
                        next.screen == "finish" && !session?.uris.isNullOrEmpty() -> "review"
                        else -> next.screen
                    }
                    next.copy(session = session, screen = screen)
                }
                saved["screen"] = if (_state.value.screen == "cleanupTransition") "deleted" else _state.value.screen
                saveSession()
                if (_state.value.screen in listOf("smart", "duplicates", "bursts")) scanCleanup()
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) {
                invalidateCleanup(emptyList())
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
    fun requestDelete(selected: Set<String>, expected: Set<PhotoIdentity>? = null) {
        if (_state.value.busy) return
        val fromGroups = _state.value.screen in listOf("duplicates", "bursts")
        val requested = (if (fromGroups) _state.value.cleanupCandidates(selected) else ReviewRules.deleteCandidates(_state.value.photos, _state.value.records, selected))
            .filter { expected == null || PhotoIdentity(it.uri, it.signature) in expected }
        if (requested.isEmpty()) return
        if (fromGroups) {
            saved["trashReturn"] = _state.value.screen
            _state.update { it.copy(trashReturn = it.screen) }
        }
        _deleteProgress.value = DeleteProgress()
        _state.update { it.copy(busy = true) }
        runDeletion {
            // Reconcile durable successes from an interrupted operation before starting a new one.
            val recovered = withContext(Dispatchers.IO) { deletion.recoverInterrupted() }
            if (recovered is DeleteStep.Complete) applyDeleteReport(recovered.report)
            val available = _state.value.photos.associateBy { PhotoIdentity(it.uri, it.signature) }
            val candidates = requested.mapNotNull { available[PhotoIdentity(it.uri, it.signature)] }
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
        saved["deletedVideoCount"] = report.deleted.count { android.net.Uri.parse(it.uri).pathSegments.contains("video") }
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
            deletedVideoCount = saved["deletedVideoCount"] ?: 0, deletedCount = deleted.size, deletedBytes = bytes, deleteReportId = report.id,
            showRatingInvitation = if (deleted.isNotEmpty()) ratingInvitation.isVisible() else it.showRatingInvitation,
            deleteFailedCount = report.failed.size,
            deleteCanceledCount = report.canceled.size, screen = if (deferResult && deleted.isNotEmpty()) "cleanupTransition" else screen, canUndo = false, message = notice, session = it.session?.reconcile(library.photos)) }
        invalidateCleanup(_state.value.photos)
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
