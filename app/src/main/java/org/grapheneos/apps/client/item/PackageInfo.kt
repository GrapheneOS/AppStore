package org.grapheneos.apps.client.item

import org.grapheneos.apps.client.App

/**
 * This data class hold everything about a package name including
 * Package name, active session id {@link PackageInstaller.EXTRA_SESSION_ID} ,
 * Installed info (like required installation or update available etc)
 * Downloading/Installing Info {@link TaskInfo}
 * @property id the package name as Id.
 * */
data class PackageInfo(
    val id: String,
    val sessionInfo: SessionInfo,
    val selectedVariant: PackageVariant,
    val allVariant: List<PackageVariant>,
    val taskInfo: TaskInfo = TaskInfo(-1, "", App.DOWNLOAD_TASK_FINISHED),
    val downloadStatus: DownloadStatus? = null,
    val installStatus: InstallStatus
) {

    fun withUpdatedInstallStatus(newStatus: InstallStatus) = PackageInfo(
        id, sessionInfo, selectedVariant, allVariant, taskInfo,
        downloadStatus, newStatus
    )

    fun withUpdatedDownloadStatus(newStatus: DownloadStatus?) = PackageInfo(
        id, sessionInfo, selectedVariant, allVariant, taskInfo,
        newStatus, installStatus
    )

    fun withUpdatedSession(newSessionInfo: SessionInfo) = PackageInfo(
        id, newSessionInfo, selectedVariant, allVariant, taskInfo,
        downloadStatus, installStatus
    )

    fun withUpdatedTask(newTaskInfo: TaskInfo) = PackageInfo(
        id, sessionInfo, selectedVariant, allVariant, newTaskInfo,
        downloadStatus, installStatus
    )

    fun withUpdatedVariant(newVariant: PackageVariant) = PackageInfo(
        id, sessionInfo, newVariant, allVariant, taskInfo,
        downloadStatus, installStatus
    )

}