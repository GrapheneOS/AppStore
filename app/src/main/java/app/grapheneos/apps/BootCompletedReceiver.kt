package app.grapheneos.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // The purpose of this receiver is to schedule the auto-update JobScheduler job on first
        // boot of the OS.
        // Scheduling code is called from ApplicationImpl which is called before this method.
        check(intent!!.action == Intent.ACTION_BOOT_COMPLETED)
    }
}
