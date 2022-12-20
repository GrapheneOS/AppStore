package app.grapheneos.apps

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.text.format.Formatter
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.appResources
import app.grapheneos.apps.core.InstallTask
import app.grapheneos.apps.ui.DetailsScreen
import app.grapheneos.apps.util.checkMainThread
import app.grapheneos.apps.util.getParcelableExtra

const val EXTRA_NOTIFICATION = "notif"

// The purpose of this service is to keep the app process alive while user initiated package
// download is running, and to make sure download notification is removed if app process is killed
// (foreground service notification is removed automatically by the OS)
class PackageDownloadFgService : Service() {

    companion object {
        private var prevNotifState: Pair<String, String>? = null

        fun invalidateNotifStateCache() {
            checkMainThread()
            prevNotifState = null
        }

        fun createNotification(tasks: List<InstallTask>): Notification? {
            checkMainThread()

            val downloading = tasks.filter { it.state <= InstallTask.STATE_DOWNLOADING }
            if (downloading.isEmpty()) {
                return null
            }

            val progress = downloading.sumOf { it.downloadProgress.get() }
            val total = downloading.sumOf { it.downloadTotal }
            val remaining = total - progress
            val remainingStr = Formatter.formatShortFileSize(appContext, remaining)

            val title = appResources.getString(R.string.notif_download_in_progress, remainingStr)
            val text = downloading.map { it.rPackage.label }.joinToString()

            Pair(title, text).let {
                if (it == prevNotifState) {
                    return null
                }
                prevNotifState = it
            }

            return Notifications.builder(Notifications.CH_PACKAGE_DOWNLOAD).run {
                setSmallIcon(R.drawable.ic_downloading)
                setContentTitle(title)
                setContentText(text)
                // Showing a progress bar would be confusing, because the download total changes
                // when new packages are enqueued/dequeued.
                // Showing a separate download notification for each package would be much more
                // complex and would leave leftover notifications after app process kill
                setContentIntent(DetailsScreen.createPendingIntent(downloading.last().rPackage.packageName))
                setShowWhen(false)
                setAutoCancel(false)
                build()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val notif = getParcelableExtra<Notification>(intent, EXTRA_NOTIFICATION)!!
            startForeground(Notifications.ID_PACKAGE_DOWNLOAD, notif)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
