package com.isaivazhi.app.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNotificationActionRouterTest {

    @Test
    fun refreshQueueInvokesCallback() {
        var invoked = false

        val handled = PlaybackNotificationActionRouter.handle("refresh_queue") {
            invoked = true
        }

        assertTrue(handled)
        assertTrue(invoked)
    }

    @Test
    fun unrelatedActionDoesNotInvokeCallback() {
        var invoked = false

        val handled = PlaybackNotificationActionRouter.handle("favorite") {
            invoked = true
        }

        assertFalse(handled)
        assertFalse(invoked)
    }
}
