package org.grapheneos.apps.client.ui.container

import androidx.lifecycle.ViewModel

class MainActivityState : ViewModel() {
    /** Whether to show app info or not on ACTION_SHOW_APP_INFO intent */
    var shouldShowAppInfo = true
}
