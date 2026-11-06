package com.maxdevlab.cleaner.photo.platform

import android.content.Context
import com.maxdevlab.cleaner.photo.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Two bounded decoders; one owner of results and batched cache writes. */
class SmartPhotoAnalyzer internal constructor(
    private val cache: FingerprintStore,
    private val decode: suspend (Photo) -> PhotoFingerprint?,
) {
    constructor(context: Context) : this(SqliteFingerprintStore(context), PhotoFingerprintDecoder(context)::decode)

    data class Result(val groups: List<CleanupGroup>, val skipped: Int)
    data class Progress(val scanned: Int, val groups: List<CleanupGroup>? = null)
    private val scanLock = Mutex()

    suspend fun analyze(photos: List<Photo>, records: Map<String, ReviewRecord>, progress: (Progress) -> Unit): Result =
        withContext(Dispatchers.IO) {
            // A canceled decode may still be returning from native code. Never overlap scan generations.
            scanLock.withLock {
                try {
                    val images = photos.filterNot { it.isVideo }
                    val cached = cache.read()
                    val results = HashMap<String, PhotoFingerprint>(images.size)
                    val pending = images.filter { photo ->
                        val entry = cached[photo.uri]?.takeIf { it.signature == photo.signature }
                        if (entry != null) results[photo.uri] = entry.fingerprint
                        entry == null
                    }
                    var scanned = results.size
                    var skipped = 0
                    fun groups() = SmartCleanupRules.analyze(images, results, records)
                    // Cached groups appear immediately, even if newly added photos still need decoding.
                    currentCoroutineContext().ensureActive()
                    val previousGroups = groups()
                    progress(Progress(scanned, previousGroups))
                    if (pending.isEmpty()) return@withLock Result(previousGroups, skipped)
                    var lastPublished = System.nanoTime()
                    val candidates = SmartCleanupRules.burstCandidates(images)
                    val (burstFirst, rest) = pending.partition { it.uri in candidates }
                    for (batch in (burstFirst + rest).chunked(BATCH_SIZE)) {
                        currentCoroutineContext().ensureActive()
                        val entries = coroutineScope {
                            // Only two coroutines per batch, rather than one coroutine per library item.
                            (0 until WORKERS).map { worker -> async {
                                batch.filterIndexed { index, _ -> index % WORKERS == worker }.map { photo ->
                                    ensureActive()
                                    photo to decode(photo)
                                }
                            } }.awaitAll().flatten()
                        }
                        currentCoroutineContext().ensureActive()
                        val writes = entries.mapNotNull { (photo, fingerprint) ->
                            if (fingerprint == null) { skipped++; null }
                            else {
                                results[photo.uri] = fingerprint
                                CachedFingerprint(photo.uri, photo.signature, fingerprint)
                            }
                        }
                        // Commit each small batch before publishing progress; cancellation loses at most one batch.
                        cache.write(writes)
                        scanned += batch.size
                        val now = System.nanoTime()
                        val partial = if (now - lastPublished >= PUBLISH_INTERVAL_NS && scanned < images.size) {
                            lastPublished = now
                            groups()
                        } else null
                        progress(Progress(scanned, partial))
                    }
                    currentCoroutineContext().ensureActive()
                    Result(groups(), skipped)
                } finally { cache.close() }
            }
        }

    private companion object {
        const val WORKERS = 2
        const val BATCH_SIZE = 64
        const val PUBLISH_INTERVAL_NS = 750_000_000L
    }
}
