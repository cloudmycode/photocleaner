package com.maxdevlab.cleaner.photo.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.size.Precision
import coil.size.Scale
import android.app.ActivityManager
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.maxdevlab.cleaner.photo.R
import com.maxdevlab.cleaner.photo.images.PreviewPolicy
import com.maxdevlab.cleaner.photo.images.PreviewSize
import coil.request.ImageRequest
import com.maxdevlab.cleaner.photo.data.*

@Composable internal fun Brand() {
    DesignText(stringResource(R.string.app_name), size = 23, weight = FontWeight(650), spacing = -.9f)
}
@Composable internal fun EmptyState(title: String, body: String) { Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(42.dp), tint = Accent); Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium); Text(body, textAlign = TextAlign.Center, fontSize = 13.sp, color = Muted) } }
@Composable internal fun rememberPhotoRequest(photo: Photo, preview: PreviewSize? = null): ImageRequest {
    val context = LocalContext.current
    return remember(context, photo.uri, photo.signature, preview) {
        val key = "${photo.uri}:${photo.signature}" + (preview?.let { ":preview:${it.width}x${it.height}" } ?: ":thumbnail")
        ImageRequest.Builder(context).data(photo.uri).memoryCacheKey(key).placeholderMemoryCacheKey(key)
            .diskCachePolicy(CachePolicy.DISABLED).crossfade(false).apply {
                if (photo.isVideo) decoderFactory { result, options, _ -> coil.decode.VideoFrameDecoder(result.source, options) }
                if (preview != null) size(preview.width, preview.height).scale(Scale.FIT).precision(Precision.EXACT)
            }.build()
    }
}

@Composable internal fun PhotoImage(photo: Photo, modifier: Modifier, scale: ContentScale = ContentScale.Crop,
    background: Color = Soft, preview: PreviewSize? = null, onReady: () -> Unit = {},
    roundedFit: Boolean = false, onImageSize: (IntSize) -> Unit = {}) {
    val request = rememberPhotoRequest(photo, preview)
    val loader = LocalContext.current.imageLoader
    var loading by remember(request) { mutableStateOf(loader.memoryCache?.get(request.memoryCacheKey!!) == null) }
    var failed by remember(request) { mutableStateOf(false) }
    var displayedSize by remember(request) { mutableStateOf(IntSize.Zero) }
    val corners = if (roundedFit) Modifier.clip(remember(displayedSize) {
        FittedPhotoShape(displayedSize.width, displayedSize.height)
    }) else Modifier
    Box(modifier.then(corners).background(background)) {
        AsyncImage(model = request, imageLoader = loader, contentDescription = "Photo from ${photo.dateLabel}",
            contentScale = scale, modifier = Modifier.fillMaxSize(),
            onLoading = { loading = true; failed = false }, onSuccess = {
                loading = false; failed = false
                displayedSize = IntSize(it.result.drawable.intrinsicWidth.coerceAtLeast(0), it.result.drawable.intrinsicHeight.coerceAtLeast(0))
                onImageSize(displayedSize); onReady()
            },
            onError = { loading = false; failed = true; if (photo.isVideo) { onImageSize(IntSize(photo.width, photo.height)); onReady() } })
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp)
        if (photo.isVideo) DesignText("▶ ${videoTime(photo.durationMs)}", Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = .65f)).padding(4.dp), color = Color.White, size = 11)
        if (failed) Icon(Icons.Outlined.BrokenImage, "Photo unavailable", Modifier.align(Alignment.Center), tint = Muted)
    }
}

/** Keep only one look-ahead request; disposal cancels work when leaving or changing the month. */
@Composable internal fun PreloadPhoto(photo: Photo, preview: PreviewSize) {
    val loader = LocalContext.current.imageLoader
    val request = rememberPhotoRequest(photo, preview)
    DisposableEffect(loader, request) {
        val task = loader.enqueue(request)
        onDispose { task.dispose() }
    }
}

/** Prepare one bounded detail bitmap while idle, and keep it for this photo's lifetime.
 * Nothing loads, swaps or gets evicted in response to a pinch. The front card draws one image.
 */
@Composable internal fun ReviewPhotoImage(photo: Photo, preview: PreviewSize, modifier: Modifier,
    interacting: () -> Boolean, onImageSize: (IntSize) -> Unit) {
    if (photo.isVideo) {
        PhotoImage(photo, modifier, ContentScale.Fit, Color.Transparent, preview, onImageSize = onImageSize)
        return
    }
    val context = LocalContext.current
    val loader = context.imageLoader
    val latestInteraction by rememberUpdatedState(interacting)
    var previewReady by remember(photo.uri, photo.signature, preview) { mutableStateOf(false) }
    val size = remember(photo.width, photo.height, context) {
        PreviewPolicy.detailSize(photo.width, photo.height, context.getSystemService(ActivityManager::class.java).memoryClass)
            ?.takeIf { it.width > preview.width || it.height > preview.height }
    }
    val baseRequest = rememberPhotoRequest(photo, size ?: preview)
    val detail by produceState<ImageBitmap?>(null, baseRequest, size) {
        value = null
        if (size == null) return@produceState
        suspend fun awaitIdle() {
            do {
                snapshotFlow { previewReady && !latestInteraction() }.first { it }
                delay(350)
            } while (latestInteraction())
        }
        awaitIdle()
        // Owned only by this front card; detail must not evict the next-photo previews.
        val request = baseRequest.newBuilder().memoryCachePolicy(CachePolicy.DISABLED).build()
        val bitmap = (loader.execute(request).drawable as? BitmapDrawable)?.bitmap ?: return@produceState
        withContext(Dispatchers.Default) { bitmap.prepareToDraw() }
        awaitIdle() // Never switch resolution under the user's fingers or during spring return.
        value = bitmap.asImageBitmap()
    }
    val bitmap = detail
    if (bitmap != null) Image(bitmap, "Photo from ${photo.dateLabel}",
        modifier.clip(remember(bitmap.width, bitmap.height) { FittedPhotoShape(bitmap.width, bitmap.height) }),
        contentScale = ContentScale.Fit)
    else PhotoImage(photo, modifier, ContentScale.Fit, Color.Transparent, preview,
        onReady = { previewReady = true }, roundedFit = true, onImageSize = onImageSize)
}
