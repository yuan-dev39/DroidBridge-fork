package com.droidbridge.root

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo

/**
 * Holds the user-approved MediaProjection session while DroidBridge is actively using capture.
 * Android requires a foreground service and fresh consent for every new projection session.
 */
class MediaProjectionSessionService : Service() {
    private var projection: MediaProjection? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null || resultCode == 0) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(com.droidbridge.ui.R.string.app_name))
            .setContentText("Screen capture session is active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)?.also { active ->
                active.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopSelf()
                    }
                }, Handler(Looper.getMainLooper()))
            }
            if (projection == null) stopSelf(startId)
            START_NOT_STICKY
        } catch (_: SecurityException) {
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "droidbridge_media_projection"
        private const val NOTIFICATION_ID = 18768
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun createStartIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, MediaProjectionSessionService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
    }
}
