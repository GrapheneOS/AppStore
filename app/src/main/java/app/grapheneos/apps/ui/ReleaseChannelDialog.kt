package app.grapheneos.apps.ui

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import app.grapheneos.apps.core.ReleaseChannel
import app.grapheneos.apps.PackageStates
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.grapheneos.apps.R

class ReleaseChannelDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val pkgName = navArgs<ReleaseChannelDialogArgs>().value.pkgName
        val pkgState = PackageStates.getPackageState(pkgName)

        val channels = ReleaseChannel.entries.reversed()

        val channelNames = channels.map { resources.getText(it.uiName) }
        val curIndex = channels.indexOf(pkgState.preferredReleaseChannel())

        return MaterialAlertDialogBuilder(requireContext()).run {
            setTitle(R.string.release_channel)
            setSingleChoiceItems(channelNames.toTypedArray(), curIndex) { _, index ->
                PackageStates.setPreferredChannelOverride(pkgState, channels[index])
                findNavController().popBackStack()
            }
            create()
        }
    }
}
