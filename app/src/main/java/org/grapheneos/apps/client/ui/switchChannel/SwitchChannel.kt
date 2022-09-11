package org.grapheneos.apps.client.ui.switchChannel

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

class SwitchChannel : DialogFragment() {

    private val args by lazy {
        navArgs<SwitchChannelArgs>().value
    }
    private val app: App by lazy {
        requireContext().applicationContext as App
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        // 'default' is not an actual channel but an indicator to use the global default channel.
        val channels = mutableListOf(getString(R.string.default_channel_indicator))
        val initialChannelSize = channels.size
        var preSelectedIndex = 0
        var selectedIndex = preSelectedIndex
        for (i in 0 until args.channels.size) {
            val name = args.channels[i]
            channels.add(name)
            if (args.selectedChannel == name) {
                preSelectedIndex = initialChannelSize + i
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(resources.getString(R.string.release_channel))
            .setSingleChoiceItems(channels.toTypedArray(), preSelectedIndex) { _, index ->
                selectedIndex = index
            }
            .setCancelable(true)
            .setPositiveButton(resources.getString(R.string.select)) { _, _ ->
                val channel = channels[selectedIndex]
                if (channel == getString(R.string.default_channel_indicator)) {
                    app.resetPackageChannel(args.pkgName)
                } else {
                    app.handleOnVariantChange(args.pkgName, channel)
                }
                findNavController().popBackStack()
            }
            .setNegativeButton(resources.getString(R.string.cancel)) { _, _ ->
                findNavController().popBackStack()
            }

        return dialog.create()
    }

}