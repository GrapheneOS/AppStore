package org.grapheneos.apps.client.ui.installError

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.grapheneos.apps.client.R

class InstallError : DialogFragment() {

    private val args by navArgs<InstallErrorArgs>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.installationFailed))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
            }
            .setMessage(args.error.description)
            .create()
    }

}