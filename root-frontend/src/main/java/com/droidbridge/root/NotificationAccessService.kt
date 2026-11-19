package com.droidbridge.root

import android.service.notification.NotificationListenerService

/**
 * Declares a user-authorized notification-listener endpoint for Android's special-access screen.
 * The service deliberately does not persist or forward notification contents.
 */
class NotificationAccessService : NotificationListenerService()
