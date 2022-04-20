package org.grapheneos.apps.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // do nothing, initialization code is in App.onCreate() which is called before onReceive()
        check(intent!!.action == Intent.ACTION_BOOT_COMPLETED)
    }
}
