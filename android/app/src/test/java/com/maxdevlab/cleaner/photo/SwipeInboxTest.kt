package com.maxdevlab.cleaner.photo

import androidx.compose.ui.geometry.Offset
import com.maxdevlab.cleaner.photo.data.Photo
import com.maxdevlab.cleaner.photo.ui.gestures.SwipeInbox
import org.junit.Assert.*
import org.junit.Test

class SwipeInboxTest {
    private val a = Photo("a", "a.jpg", 1, 100, 400, 300, 1)
    private val b = a.copy(uri = "b")
    @Test fun oneCompleteStrokeIsDeliveredOnceToItsOriginalPhoto() {
        val inbox = SwipeInbox()
        assertTrue(inbox.begin(a))
        inbox.drag(Offset(3f, -300f))
        assertEquals(Offset(3f, -300f), inbox.release()!!.offset)
        assertNull(inbox.release())
        assertNotNull(inbox.take(a)); assertNull(inbox.take(a))
    }
    @Test fun queuedStrokeCannotBeReplacedRetargetedOrChangedByLateMoves() {
        val inbox = SwipeInbox()
        inbox.begin(a); inbox.drag(Offset(0f, 300f)); inbox.release()
        assertFalse(inbox.begin(b))
        inbox.drag(Offset(0f, -900f))
        assertNull(inbox.take(b))
        assertNull(inbox.take(a.copy(modified = 2)))
        assertEquals(Offset(0f, 300f), inbox.take(a)!!.offset)
    }
    @Test fun cancellationOrLifecycleResetLeavesNoDeferredAction() {
        val inbox = SwipeInbox()
        inbox.begin(a); inbox.drag(Offset(0f, 500f)); inbox.cancelActive()
        assertNull(inbox.release()); assertNull(inbox.take(a))
        inbox.begin(b); inbox.release(); inbox.clear()
        assertNull(inbox.take(b)); assertTrue(inbox.begin(a))
    }
    @Test fun endOfARejectedGestureDoesNotCancelTheAlreadyQueuedStroke() {
        val inbox = SwipeInbox()
        inbox.begin(a); inbox.release()
        assertFalse(inbox.begin(b))
        assertNull(inbox.cancelActive())
        assertNotNull(inbox.take(a))
    }
}
