package org.grapheneos.apps.client.utils

import android.content.Context
import android.os.UserManager
import org.grapheneos.apps.client.App

fun Context.isInstallBlockedByAdmin(): Boolean {
    val um = getSystemService(UserManager::class.java)

    return if ((applicationContext as App).isPrivilegeMode) {
        um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
    } else {
        um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS) ||
                um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
                um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
    }
}

fun Context.isUninstallBlockedByAdmin(): Boolean {
    val um = getSystemService(UserManager::class.java)
    return um.hasUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS)
}