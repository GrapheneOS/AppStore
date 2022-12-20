package app.grapheneos.apps.util

object InternalSettings {
    val file = getSharedPreferences("internal_settings")

    const val KEY_DELETED_v1_NOTIF_CHANNELS = "deleted_v1_notif_channels"
    const val KEY_DELETED_v1_FILES = "deleted_v1_files"

    const val KEY_SUPPRESS_NOTIFICATION_PERMISSION_DIALOG = "suppress_notification_permission_dialog"
}
