package app.grapheneos.apps.ui

import android.app.Dialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.core.content.getSystemService
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.navigation.NavDeepLinkBuilder
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import app.grapheneos.apps.R
import app.grapheneos.apps.core.ErrorTemplate
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.util.PendingDialog
import app.grapheneos.apps.util.componentName
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ErrorDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val template: ErrorTemplate = navArgs<ErrorDialogArgs>().value.template

        val ctx = requireContext()

        return MaterialAlertDialogBuilder(ctx).run {
            val title = template.title(ctx)
            val message = template.message(ctx)

            setTitle(title)
            setMessage(message)

            setPositiveButton(R.string.dismiss, null)
            setNeutralButton(R.string.btn_copy) { _, _ ->
                val cd = ClipData.newPlainText(title, message)
                appContext.getSystemService<ClipboardManager>()!!.setPrimaryClip(cd)
            }
            create()
        }
    }

    companion object {
        fun createArgs(template: ErrorTemplate) = ErrorDialogArgs.Builder(template).build().toBundle()

        fun createPendingDialog(template: ErrorTemplate) =
            PendingDialog(R.id.error_dialog, createArgs(template))

        fun show(fragment: Fragment, template: ErrorTemplate) {
            fragment.findNavController().navigate(R.id.error_dialog, createArgs(template))
        }

        fun createPendingIntent(template: ErrorTemplate): PendingIntent =
            NavDeepLinkBuilder(appContext).run {
                setGraph(R.navigation.nav_graph)
                setDestination(R.id.error_dialog, createArgs(template))
                setComponentName(componentName<MainActivity>())
                createPendingIntent()
            }
    }
}
