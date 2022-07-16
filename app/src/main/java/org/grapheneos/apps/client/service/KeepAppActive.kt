package org.grapheneos.apps.client.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Observer
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

class KeepAppActive : Service() {

    private lateinit var notificationMgr: NotificationManagerCompat
    private val app: App by lazy { applicationContext as App }

    private val observer = Observer<Boolean> { isDownloadRunning ->
        if (!isDownloadRunning) stopService()
    }


    private fun stopService() {
        stopSelf()
        notificationMgr.cancelAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationMgr = NotificationManagerCompat.from(this)
        app.updateServiceStatus(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        app.isDownloadRunning().observeForever(observer)
        val notification = Notification.Builder(this, App.BACKGROUND_SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_downloading)
            .setContentTitle(App.getString(R.string.packagesDownloading))
            .setOnlyAlertOnce(true)
            .build()
        notification.flags = Notification.FLAG_FOREGROUND_SERVICE
        startForeground(1, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        app.isDownloadRunning().removeObserver(observer)
        app.updateServiceStatus(false)
    }

}
