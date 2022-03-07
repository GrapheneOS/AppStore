package org.grapheneos.apps.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class APKUninstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        /*App class have active listener for uninstall event*/
        if (intent == null || context == null) return
        val unknownCode = -999

        /*handle STATUS_PENDING_USER_ACTION for uninstall event */
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, unknownCode)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmationIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    (context).startActivity(it)
                }
            }
        }
    }
}