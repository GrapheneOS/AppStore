package org.grapheneos.apps.client.utils

import android.app.Application
import android.content.Context
import android.os.UserManager

fun Context.isInstallBlockedByAdmin(): Boolean {
    val um = getSystemService(UserManager::class.java)

    return um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS) ||
            um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            um.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
}
