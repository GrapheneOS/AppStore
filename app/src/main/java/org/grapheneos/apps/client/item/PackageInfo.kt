package org.grapheneos.apps.client.item

import android.content.Context
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.utils.network.getDownloadRootDir
import org.grapheneos.apps.client.utils.network.getResultDir
import org.grapheneos.apps.client.utils.network.getResultRootDir
import java.io.File

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

    companion object {
        fun PackageInfo.cleanCachedFiles(context: Context) {
            val installedVersion = installStatus.installedVersion
            val latestVersion = selectedVariant.versionCode

            //if the app is installed purge any cached apk files
            if (installedVersion == latestVersion) {
                selectedVariant.apply {
                    getResultRootDir(context).deleteRecursively()
                    getDownloadRootDir(context).deleteRecursively()

                }
            } else {
                // else only purge unneeded cached apk files
                selectedVariant.apply {
                    getResultRootDir(context).cleanOldFiles(getResultDir(context))
                    getDownloadRootDir(context).cleanOldFiles(getResultDir(context))
                }
            }
        }

        private fun File.cleanOldFiles(currentFile: File) {
            if (exists()) {
                list()?.forEach { path ->
                    val dir = File("${absolutePath}/$path")
                    if (currentFile.absolutePath != dir.absolutePath) {
                        dir.deleteRecursively()
                    }
                }
            }
        }
    }

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
