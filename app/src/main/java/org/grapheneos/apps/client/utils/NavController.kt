package org.grapheneos.apps.client.utils

import androidx.navigation.NavController
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.ui.syncScreen.SyncScreenArgs

fun NavController.navigateToSyncScreen(shouldSync: Boolean = true) {
    val args = SyncScreenArgs.Builder(shouldSync).build().toBundle()
    navigate(R.id.syncScreen, args)
}
