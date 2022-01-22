package org.grapheneos.apps.client.ui.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr

class MainSettings : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = JobPsfsMgr.AUTO_UPDATE_PREFERENCE
        addPreferencesFromResource(R.xml.settings)
    }

}