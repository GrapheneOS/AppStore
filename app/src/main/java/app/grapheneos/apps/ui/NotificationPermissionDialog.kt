package app.grapheneos.apps.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.grapheneos.apps.R
import app.grapheneos.apps.core.selfPkgName
import app.grapheneos.apps.util.InternalSettings

class NotificationPermissionDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext()).run {
            setTitle(R.string.notification_permission_dialog_title)
            val msg = Html.fromHtml(getString(R.string.notification_permission_dialog_message),
                    Html.FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH)
            setMessage(msg)

            setPositiveButton(R.string.open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, selfPkgName)
                startActivity(intent)
            }
            setNegativeButton(R.string.dont_show_again) {_, _, ->
                InternalSettings.file.edit {
                    putBoolean(InternalSettings.KEY_SUPPRESS_NOTIFICATION_PERMISSION_DIALOG, true)
                }
            }
            create()
        }
    }
}
