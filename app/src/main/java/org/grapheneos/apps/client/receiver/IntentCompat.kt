package org.grapheneos.apps.client.receiver

import android.content.Intent
import android.os.Build

// Temporary until some androidX class has some compat version handling getParcelableExtra
fun Intent.getParcelableIntentExtra(name: String = Intent.EXTRA_INTENT): Intent? {
    return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        this.getParcelableExtra(name)
    } else {
        this.getParcelableExtra(name, Intent::class.java)
    }
}
