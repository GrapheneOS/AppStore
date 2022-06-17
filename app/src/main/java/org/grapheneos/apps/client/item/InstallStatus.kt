package org.grapheneos.apps.client.item

import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

sealed class InstallStatus(
    open val status: String,
    val installedVersion: Long?
) {
    companion object {
        fun InstallStatus.createFailed(error: String, status: String? = null): Failed = Failed(
            installedVersion = this.installedVersion,
            errorMsg = error,
            status = status ?: App.getString(R.string.failed)
        )

        fun InstallStatus.createPending() = Pending(this.installedVersion ?: 0L)

        fun InstallStatus.createInstalling(isInstalling: Boolean, canCancelTask: Boolean) =
            Installing(
                installedVersion = this.installedVersion ?: 0L,
                canCancelTask = canCancelTask,
                isInstalling = isInstalling
            )
    }

    class Installable : InstallStatus(App.getString(R.string.install), null)

    class Installed(installedVersion: Long) :
        InstallStatus(App.getString(R.string.open), installedVersion)

    class Disabled(installedVersion: Long) :
        InstallStatus(App.getString(R.string.btn_enable_package), installedVersion)

    class Updatable(installedVersion: Long) :
        InstallStatus(App.getString(R.string.update), installedVersion)

    class Pending(installedVersion: Long) :
        InstallStatus(App.getString(R.string.pending_install), installedVersion)

    class Installing(
        val isInstalling: Boolean,
        installedVersion: Long,
        val canCancelTask: Boolean
    ) : InstallStatus(App.getString(R.string.installing), installedVersion)

    class Updated(installedVersion: Long) :
        InstallStatus(App.getString(R.string.open), installedVersion)

    class Uninstalling(
        val isUninstalling: Boolean,
        installedVersion: Long,
        val canCancelTask: Boolean = false
    ) : InstallStatus(App.getString(R.string.uninstalling), installedVersion)

    class NewerVersionInstalled(installedVersion: Long) :
        InstallStatus(App.getString(R.string.open), installedVersion)

    class ReinstallRequired(installedVersion: Long) :
        InstallStatus(App.getString(R.string.reinstall), installedVersion)

    class Failed(
        status: String = App.getString(R.string.failed),
        installedVersion: Long?, val errorMsg: String
    ) : InstallStatus(status, installedVersion)
}
