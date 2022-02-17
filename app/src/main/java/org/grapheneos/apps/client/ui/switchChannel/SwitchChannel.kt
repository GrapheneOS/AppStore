package org.grapheneos.apps.client.ui.switchChannel

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.grapheneos.apps.client.App

class SwitchChannel : DialogFragment() {

    private val args by lazy {
        navArgs<SwitchChannelArgs>().value
    }
    private val app: App by lazy {
        requireContext().applicationContext as App
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        val channels = mutableListOf<String>()
        var preSelectedIndex = 0
        var selectedIndex = preSelectedIndex
        for (i in 0 until args.channels.size) {
            val name = args.channels[i]
            channels.add(name)
            if (args.selectedChannel == name) {
                preSelectedIndex = i
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select release channel")
            .setSingleChoiceItems(channels.toTypedArray(), preSelectedIndex) { _, index ->
                selectedIndex = index
            }
            .setCancelable(true)
            .setPositiveButton("ok") { _, _ ->
                app.handleOnVariantChange(args.pkgName, channels[selectedIndex])
                findNavController().popBackStack()
            }
            .setNegativeButton("cancel") { _, _ ->
                findNavController().popBackStack()
            }

        return dialog.create()
    }

}