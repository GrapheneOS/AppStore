package org.grapheneos.apps.client.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build

// Maybe temporary until androidX core library is updated to 1.9.0 stable
fun Context.registerReceiverExportedCompat(br: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this.registerReceiver(br, filter)
    } else {
        this.registerReceiver(br, filter, Context.RECEIVER_EXPORTED)
    }
}

fun Context.registerReceiverNotExportedCompat(br: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this.registerReceiver(br, filter)
    } else {
        this.registerReceiver(br, filter, Context.RECEIVER_NOT_EXPORTED)
    }
}
