package org.grapheneos.apps.client.item

import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

sealed class InstallStatus(
    open val status: String,
    val installedV: String = "N/A",
) {
    companion object {
        private fun Long?.toApkVersion(): String {
            return if (this == null || this <= 0) "N/A" else this.toString()
        }

        fun InstallStatus.createFailed(error: String, status: String? = null): Failed = Failed(
            installedVersion = this.installedV.toLongOrNull() ?: 0L,
            errorMsg = error,
            status = status ?: App.getString(R.string.failed)
        )

        fun InstallStatus.createPending() = Pending(
            installedVersion = this.installedV.toLongOrNull() ?: 0L,
        )

        fun InstallStatus.createInstalling(isInstalling: Boolean, canCancelTask: Boolean) =
            Installing(
                installedVersion = this.installedV.toLongOrNull() ?: 0L,
                canCancelTask = canCancelTask,
                isInstalling = isInstalling
            )
    }

    class Installable : InstallStatus(App.getString(R.string.install))

    data class Installed(
        val installedVersion: Long,
    ) : InstallStatus(
        App.getString(R.string.open),
        installedV = installedVersion.toApkVersion()
    )

    data class Updatable(
        val installedVersion: Long,
    ) : InstallStatus(
        App.getString(R.string.update),
        installedV = installedVersion.toApkVersion()
    )

    data class Pending(
        val installedVersion: Long,
    ) : InstallStatus(
        App.getString(R.string.pending_install),
        installedV = installedVersion.toApkVersion()
    )

    data class Installing(
        val isInstalling: Boolean,
        val installedVersion: Long,
        val canCancelTask: Boolean
    ) : InstallStatus(
        App.getString(R.string.installing),
        installedV = installedVersion.toApkVersion()
    )

    data class Updated(
        val installedVersion: Long,
    ) : InstallStatus(
        App.getString(R.string.open),
        installedVersion.toApkVersion(),
    )

    data class Uninstalling(
        val isUninstalling: Boolean,
        val installedVersion: Long,
        val canCancelTask: Boolean = false
    ) : InstallStatus(
        App.getString(R.string.uninstalling),
        installedV = installedVersion.toApkVersion()
    )

    data class NewerVersionInstalled(
        val installedVersion: Long,
    ) : InstallStatus(
        App.getString(R.string.open),
        installedV = installedVersion.toApkVersion(),
    )

    data class ReinstallRequired(
        val installedVersion: Long,
    ) : InstallStatus(
        App.getString(R.string.reinstall),
        installedV = installedVersion.toApkVersion(),
    )

    data class Failed(
        override val status: String = App.getString(R.string.failed),
        val installedVersion: Long,
        val errorMsg: String
    ) : InstallStatus(
        status,
        installedV = installedVersion.toApkVersion()
    )
}
