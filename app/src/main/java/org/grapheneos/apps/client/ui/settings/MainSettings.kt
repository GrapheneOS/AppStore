package org.grapheneos.apps.client.ui.settings

import android.os.Bundle
import android.view.View
import androidx.core.view.doOnPreDraw
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.transition.MaterialSharedAxis
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
    }

}