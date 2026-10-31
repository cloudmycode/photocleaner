package com.maxdevlab.cleaner.photo.platform

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.maxdevlab.cleaner.photo.data.*

fun androidDeletionBackend(context: Context): DeletionBackend<IntentSender> = when {
    Build.VERSION.SDK_INT >= 30 -> BatchDeletionBackend(context)
    Build.VERSION.SDK_INT >= 29 -> ScopedDeletionBackend(context)
    else -> LegacyDeletionBackend(context)
}
private open class LegacyDeletionBackend(protected val context: Context) : DeletionBackend<IntentSender> {
    protected val source = MediaStorePhotoSource(context)
    private val scope = TestMediaScope(context)
    override val mode = DeleteMode.LEGACY
    override fun needsWritePermission() = PhotoAccessPolicy(context).needsWritePermission()
    override fun isCurrent(item: DeleteItem) = source.isCurrent(item.uri, item.signature)
    override fun deleteOne(item: DeleteItem): OneDelete<IntentSender> {
        if (!scope.allows(item.uri)) return OneDelete.Failed
        return if (context.contentResolver.delete(Uri.parse(item.uri), null, null) > 0) OneDelete.Deleted else OneDelete.Failed
    }
    override fun batchConsent(items: List<DeleteItem>): IntentSender = error("Batch confirmation is unavailable")
}
@RequiresApi(29)
private class ScopedDeletionBackend(context: Context) : LegacyDeletionBackend(context) {
    override val mode = DeleteMode.SCOPED
    override fun deleteOne(item: DeleteItem): OneDelete<IntentSender> = try { super.deleteOne(item) }
        catch (e: RecoverableSecurityException) { OneDelete.NeedsConsent(e.userAction.actionIntent.intentSender) }
}
@RequiresApi(30)
private class BatchDeletionBackend(context: Context) : LegacyDeletionBackend(context) {
    override val mode = DeleteMode.BATCH
    override fun currentUris(items: List<DeleteItem>) = source.currentUris(items)
    override fun batchConsent(items: List<DeleteItem>): IntentSender {
        check(items.all { TestMediaScope(context).allows(it.uri) })
        return MediaStore.createDeleteRequest(context.contentResolver, items.map { Uri.parse(it.uri) }).intentSender
    }
}
