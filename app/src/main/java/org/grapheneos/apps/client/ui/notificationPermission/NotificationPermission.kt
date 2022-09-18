package org.grapheneos.apps.client.ui.notificationPermission

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.ui.installError.InstallErrorArgs

@AndroidEntryPoint
class NotificationPermission : DialogFragment() {

    private val args by navArgs<InstallErrorArgs>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.notification_permission_dialog_title)
            .setMessage(
                HtmlCompat.fromHtml(
                    getString(R.string.notification_permission_dialog_message),
                    HtmlCompat.FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH
                )
            ).setPositiveButton(R.string.settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts(
                    "package", requireContext().packageName, null
                )
                intent.data = uri
                startActivity(intent)
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

}