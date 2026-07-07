package com.isaivazhi.app

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PlaybackServiceTest {

    @Test
    fun activePlaybackDismissIsIgnored() {
        assertTrue(Media3PlaybackService.shouldIgnoreNotificationDismiss(true))
        assertFalse(Media3PlaybackService.shouldIgnoreNotificationDismiss(false))
    }

    @Test
    fun activePlaybackNotificationIsMarkedOngoing() {
        val notification = Notification()
        notification.deleteIntent = null

        Media3PlaybackService.applyPlaybackPersistencePolicy(
            null,
            notification,
            true,
        )

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_NO_CLEAR != 0)
        assertNull(notification.deleteIntent)
    }

    @Test
    fun inactivePlaybackNotificationRemainsDismissible() {
        val notification = Notification()
        notification.flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR

        Media3PlaybackService.applyPlaybackPersistencePolicy(
            null,
            notification,
            false,
        )

        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0, notification.flags and Notification.FLAG_NO_CLEAR)
    }
}
