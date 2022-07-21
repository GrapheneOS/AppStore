package org.grapheneos.apps.client.utils

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

// Temporary until androidX has some compat class handling getPackageInfo
fun PackageManager.getPackageInfoCompat(pkgName: String, flags: Int): PackageInfo {
    return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        this.getPackageInfo(pkgName, flags)
    } else {
        this.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    }
}
