package org.grapheneos.apps.client.item

import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

sealed class InstallStatus(
    open val status: String,
    val installedV: String = "N/A",
    val latestV: String
) {
    companion object {
        private fun Long?.toApkVersion(): String {
            return if (this == null || this <= 0) "N/A" else this.toString()
        }

        fun InstallStatus.createFailed(error: String, status: String? = null): Failed = Failed(
            installedVersion = this.installedV.toLongOrNull() ?: 0L,
            latestVersion = this.latestV.toLongOrNull() ?: 0L,
            errorMsg = error,
            status = status ?: App.getString(R.string.failed)
        )
    }

    data class Installable(
        val latestVersion: Long
    ) : InstallStatus(
        App.getString(R.string.install), latestV = latestVersion.toString()
    )

    data class Installed(
        val installedVersion: Long,
        val latestVersion: Long
    ) : InstallStatus(
        App.getString(R.string.installed).replace(":", ""),
        latestV = latestVersion.toString(),
        installedV = installedVersion.toApkVersion()
    )

    data class Updatable(
        val installedVersion: Long,
        val latestVersion: Long
    ) : InstallStatus(
        App.getString(R.string.update),
        latestV = latestVersion.toString(),
        installedV = installedVersion.toApkVersion()
    )

    data class Installing(
        val isInstalling: Boolean,
        val latestVersion: Long,
        val canCancelTask: Boolean
    ) : InstallStatus(
        App.getString(R.string.installing),
        latestV = latestVersion.toString()
    )

    data class Updated(
        val installedVersion: Long,
        val latestVersion: Long,
    ) : InstallStatus(
        App.getString(R.string.updated),
        installedVersion.toApkVersion(),
        latestVersion.toString()
    )

    data class Uninstalling(
        val isUninstalling: Boolean,
        val installedVersion: Long,
        val latestVersion: Long,
        val canCancelTask: Boolean = false
    ) : InstallStatus(
        App.getString(R.string.uninstalling),
        latestV = latestVersion.toApkVersion()
    )

    data class ReinstallRequired(
        val installedVersion: Long,
        val latestVersion: Long
    ) : InstallStatus(
        App.getString(R.string.reinstallRequired),
        installedV = installedVersion.toApkVersion(),
        latestV = latestVersion.toString()
    )

    data class Failed(
        override val status: String = App.getString(R.string.failed),
        val installedVersion: Long,
        val latestVersion: Long,
        val errorMsg: String
    ) : InstallStatus(
        status,
        latestV = latestVersion.toApkVersion()
    )
}