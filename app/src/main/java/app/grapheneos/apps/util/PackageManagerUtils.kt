@file:Suppress("DEPRECATION")

package app.grapheneos.apps.util

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.PackageManager.PackageInfoFlags
import android.content.pm.ResolveInfo
import android.content.pm.SharedLibraryInfo
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import app.grapheneos.apps.core.isPrivilegedInstaller
import app.grapheneos.apps.core.userManager

fun PackageManager.getPackageInfoOrNull(packageName: String, flags: Long = 0L): PackageInfo? {
    return try {
        getPackageInfo(packageName, flags)
    } catch (e: NameNotFoundException) {
        null
    }
}

fun PackageManager.getPackageInfo(packageName: String, flags: Long = 0L): PackageInfo {
    return if (Build.VERSION.SDK_INT >= 33) {
        val res = getPackageInfo(packageName, PackageInfoFlags.of(flags))
        // useful for testing package upgrades
        // v.versionCode -= 2
        res
    } else {
        getPackageInfo(packageName, flags.toInt())
    }
}

fun PackageManager.getInstalledPackages(flags: Long = 0L): List<PackageInfo> {
    return if (Build.VERSION.SDK_INT >= 33) {
        getInstalledPackages(PackageInfoFlags.of(flags))
    } else {
        getInstalledPackages(flags.toInt())
    }
}

fun PackageManager.getApplicationInfoOrNull(packageName: String, flags: Long = 0L): ApplicationInfo? =
    try {
        getApplicationInfo(packageName, flags)
    } catch (e: NameNotFoundException) {
        null
    }

fun PackageManager.getApplicationInfo(packageName: String, flags: Long = 0L): ApplicationInfo {
    return if (Build.VERSION.SDK_INT >= 33) {
        getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags))
    } else {
        getApplicationInfo(packageName, flags.toInt())
    }
}

fun PackageManager.resolveActivity(intent: Intent, flags: Long = 0L): ResolveInfo? {
    return if (Build.VERSION.SDK_INT >= 33) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags))
    } else {
        resolveActivity(intent, flags.toInt())
    }
}

fun PackageManager.getPackageArchiveInfo(archiveFilePath: String, flags: Long = 0L): PackageInfo? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getPackageArchiveInfo(archiveFilePath, PackageInfoFlags.of(flags))
    } else {
        getPackageArchiveInfo(archiveFilePath, flags.toInt())
    }
}

fun PackageManager.getSharedLibraries(flags: Long = 0L): List<SharedLibraryInfo> {
    return if (Build.VERSION.SDK_INT >= 33) {
        getSharedLibraries(PackageInfoFlags.of(flags))
    } else {
        getSharedLibraries(flags.toInt())
    }
}

fun PackageInfo.getVersionNameOrVersionCode() = versionName ?: versionCode.toString()

fun PackageInfo.isSystemPackage() = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
fun PackageInfo.isUpdatedSystemPackage() = applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

fun appDetailsIntent(pkgName: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(pkgName))

class UserRestrictionException : SecurityException()

@Throws(UserRestrictionException::class)
fun throwIfAppInstallationNotAllowed() {
    if (!isAppInstallationAllowed()) {
        throw UserRestrictionException()
    }
}

fun isAppInstallationAllowed(): Boolean {
    val um = userManager
    return if (isPrivilegedInstaller) {
        !um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
    } else {
        !um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            && !um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
            && !um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
    }
}
