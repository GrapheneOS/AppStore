package app.grapheneos.apps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_MIN
import android.service.notification.StatusBarNotification
import androidx.annotation.StringRes
import androidx.core.content.edit
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.appResources
import app.grapheneos.apps.core.notificationManager
import app.grapheneos.apps.util.InternalSettings
import app.grapheneos.apps.util.checkMainThread

object Notifications {
    const val ID_PACKAGE_DOWNLOAD = 1
    const val ID_AUTO_UPDATE_JOB_STATUS = 2

    private const val FIRST_DYNAMIC_ID = 1000

    const val GROUP_AUTO_UPDATE_JOB = "auto_update_job"
    const val CH_AUTO_UPDATE_ALL_UP_TO_DATE = "auto_update_job_all_up_to_date"
    const val CH_AUTO_UPDATE_UPDATED_PACKAGES = "auto_update_job_updated_pkgs"
    const val CH_AUTO_UPDATE_UPDATES_AVAILABLE = "auto_update_job_updates_available"
    const val CH_AUTO_UPDATE_CONFIRMATION_REQUIRED = "auto_update_job_confirmation_required"
    const val CH_AUTO_UPDATE_FAILED = "auto_update_job_failed"
    const val CH_BACKGROUND_UPDATE_CHECK_FAILED = "update_check_failed"

    const val CH_CONFIRMATION_REQUIRED = "confirmation_required"
    const val CH_MISSING_DEPENDENCY = "dependency_error"
    const val CH_INSTALLATION_FAILED = "installation_failed"
    const val CH_PACKAGE_DOWNLOAD = "backgroundTask" // channel name from initial version
    const val CH_PACKAGE_DOWLOAD_FAILED = "pkg_download_failed"

    fun init(activeNotifications: Array<StatusBarNotification>) {
        prevDynamicNotificationId = if (activeNotifications.isEmpty()) {
            FIRST_DYNAMIC_ID
        } else {
            maxOf(activeNotifications.maxOf { it.id }, FIRST_DYNAMIC_ID)
        }

        maybeDeleteV1Channels()

        val manager = notificationManager

        val autoUpdateChannels = listOf(
            ch(CH_AUTO_UPDATE_ALL_UP_TO_DATE, R.string.notif_auto_update_all_up_to_date, IMPORTANCE_MIN),
            ch(CH_AUTO_UPDATE_UPDATES_AVAILABLE, R.string.notif_ch_updates_available),
            ch(CH_AUTO_UPDATE_UPDATED_PACKAGES, R.string.notif_ch_auto_update_updated_packages),
            ch(CH_AUTO_UPDATE_CONFIRMATION_REQUIRED, R.string.notif_ch_auto_update_confirmation_required),
            ch(CH_AUTO_UPDATE_FAILED, R.string.notif_ch_auto_update_failed),
        )

        group(GROUP_AUTO_UPDATE_JOB, R.string.notif_group_auto_update, autoUpdateChannels).let {
            manager.createNotificationChannelGroup(it)
        }

        val channels = mutableListOf(
            ch(CH_CONFIRMATION_REQUIRED, R.string.notif_title_pending_user_action, IMPORTANCE_HIGH),
            ch(CH_PACKAGE_DOWLOAD_FAILED, R.string.notif_download_failed, IMPORTANCE_HIGH),
            ch(CH_INSTALLATION_FAILED, R.string.notif_installation_failed, IMPORTANCE_HIGH),
            ch(CH_PACKAGE_DOWNLOAD, R.string.notif_ch_downloading_app, IMPORTANCE_DEFAULT),
            ch(CH_MISSING_DEPENDENCY, R.string.notif_missing_dependency, IMPORTANCE_DEFAULT),
            ch(CH_BACKGROUND_UPDATE_CHECK_FAILED, R.string.notif_ch_background_update_check_failed)
        )

        channels.addAll(autoUpdateChannels)

        manager.createNotificationChannels(channels)
    }

    private fun group(id: String, @StringRes title: Int, channels: List<NotificationChannel>): NotificationChannelGroup {
        val g = NotificationChannelGroup(id, appResources.getText(title))
        channels.forEach {
            it.group = id
        }
        return g
    }

    private fun ch(id: String, @StringRes title: Int, importance: Int = NotificationManager.IMPORTANCE_LOW,
               silent: Boolean = true, group: String? = null): NotificationChannel {
        val c = NotificationChannel(id, appResources.getText(title), importance)
        if (silent) {
            c.setSound(null, null)
            c.enableVibration(false)
        }
        if (group != null) {
            c.group = group
        }
        c.setShowBadge(false)
        return c
    }

    fun builder(channel: String) = Notification.Builder(appContext, channel).apply {
        setGroup(channel)
        setShowWhen(true)
        setAutoCancel(true)
    }

    var prevDynamicNotificationId = FIRST_DYNAMIC_ID

    fun generateId(): Int {
        checkMainThread()
        val res = prevDynamicNotificationId++
        check(res < Int.MAX_VALUE)
        return res
    }

    fun cancel(id: Int) {
        notificationManager.cancel(id)
    }
}

fun Notification.Builder.setContentTitle(@StringRes title: Int) {
    setContentTitle(appResources.getText(title))
}

fun Notification.Builder.setContentText(@StringRes text: Int) {
    setContentText(appResources.getText(text))
}

fun Notification.Builder.show(id: Int) {
    notificationManager.notify(id, this.build())
}

private fun maybeDeleteV1Channels() {
    if (InternalSettings.file.getBoolean(InternalSettings.KEY_DELETED_v1_NOTIF_CHANNELS, false)) {
        return
    }
    val m = notificationManager
    m.deleteNotificationChannelGroup("seamlessUpdateGroup")
    m.deleteNotificationChannelGroup("installationFailedGroup")

    InternalSettings.file.edit {
        putBoolean(InternalSettings.KEY_DELETED_v1_NOTIF_CHANNELS, true)
    }
}
