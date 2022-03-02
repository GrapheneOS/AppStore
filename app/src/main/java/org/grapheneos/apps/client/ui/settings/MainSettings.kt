package org.grapheneos.apps.client.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.core.view.doOnPreDraw
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.transition.platform.MaterialSharedAxis
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr

class MainSettings : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
        }
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Y, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Y, false)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = JobPsfsMgr.AUTO_UPDATE_PREFERENCE
        addPreferencesFromResource(R.xml.settings)
        val autoInstallPref = findPreference(JobPsfsMgr.AUTO_INSTALL_KEY) ?: return
        if (requireContext().applicationContext.checkSelfPermission(
            Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
        ) {
            getPreferenceScreen().removePreference(autoInstallPref)
        }
    }

}
