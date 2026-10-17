package app.grapheneos.apps.core

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.LocaleList
import android.text.format.Formatter
import android.util.ArrayMap
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.util.appendRes
import app.grapheneos.apps.util.checkMainThread
import java.util.Collections

// Access only from the main thread
class PackageState(val pkgName: String, val id: Long) {
    lateinit var rPackage: RPackage; private set
    var installedSplits: Map<String, String> = emptyMap()
    var osPackageInfo: PackageInfo? = null
        set(value) {
            field = value
            installedSplits = getSplitsMap(value)
            onDeltaStatusChanged()
        }

    var ignoreDeltasForVersion: Long? = null
        private set

    var releaseChannelOverride: ReleaseChannel? = null
    var pkgSpecificLocales: LocaleList? = null

    var installTask: InstallTask? = null

    var pkgInstallerSessionId: Int = PackageInstaller.SessionInfo.INVALID_ID
    var waitingForPendingUserAction = false

    fun setRPackage(v: RPackage) {
        rPackage = v
        cachedDownloadSize = null
    }

    fun updateRPackage() {
        val pkg = rPackage.common.getPackage(preferredReleaseChannel())
        if (pkg !== rPackage) {
            setRPackage(pkg)
        }
    }

    fun notifyListeners() = PackageStates.dispatchStateChanged(this)

    fun isInstalling() = installTask != null || hasInstallerSession()
    fun hasInstallerSession() = pkgInstallerSessionId != PackageInstaller.SessionInfo.INVALID_ID

    enum class Status {
        NOT_INSTALLED,
        SHARED_LIBRARY,
        DISABLED,
        INSTALLING,
        OUT_OF_DATE,
        UP_TO_DATE,
        ;
    }

    var cachedDownloadSize: String? = null

    fun getDownloadSizeUiString(): String {
        cachedDownloadSize?.let { return it }

        val size = rPackage.calculateDownloadSize(this, rPackage.collectNeededApks(appResources.configuration))
        Formatter.formatShortFileSize(appContext, size).let {
            cachedDownloadSize = it
            return it
        }
    }

    fun ignoreDeltasForVersion(versionCode: Long) {
        checkMainThread()
        this.ignoreDeltasForVersion = versionCode
        onDeltaStatusChanged()
        notifyListeners()
    }

    fun onDeltaStatusChanged() {
        cachedDownloadSize = null
    }

    private fun getSplitsMap(pkgInfo: PackageInfo?): Map<String, String> {
        val appInfo = pkgInfo?.applicationInfo ?: return emptyMap()
        val baseApkPath = checkNotNull(appInfo.sourceDir)
        val baseApkName = "base.apk"
        val splitNames = appInfo.splitNames
        if (splitNames == null) {
            return Collections.singletonMap(baseApkName, baseApkPath)
        }
        val numSplits = splitNames.size
        val splitPaths = requireNotNull(appInfo.splitSourceDirs)
        check(splitPaths.size == numSplits)
        val res = ArrayMap<String, String>(numSplits + 1)
        res.put(baseApkName, baseApkPath)
        val b = StringBuilder()
        for (i in 0 until numSplits) {
            b.setLength(0)
            b.append("split_")
            b.append(checkNotNull(splitNames[i]))
            b.append(".apk")
            check(res.put(b.toString(), checkNotNull(splitPaths[i])) == null)
        }
        return res
    }

    private fun appendDots(sb: StringBuilder) {
        repeat(PackageStates.updateLoopRunnableRunCount and 0b11) {
            sb.append('.')
        }
    }

    fun statusString(ctx: Context): String {
        when (status()) {
            Status.NOT_INSTALLED ->
                return getDownloadSizeUiString()
            Status.SHARED_LIBRARY ->
                return ctx.getString(R.string.pkg_status_shared_library, getDownloadSizeUiString())
            Status.OUT_OF_DATE ->
                return ctx.getString(R.string.pkg_status_update_available, getDownloadSizeUiString())
            Status.DISABLED ->
                return ctx.getString(R.string.pkg_status_disabled)
            Status.UP_TO_DATE ->
                return ctx.getString(R.string.pkg_status_installed)
            Status.INSTALLING -> {
                val installTask = this.installTask
                if (installTask == null) {
                    check(hasInstallerSession())
                    val sb = StringBuilder()
                    val resource = if (waitingForPendingUserAction)
                        R.string.pkg_status_waiting_for_confirmation
                    else
                        R.string.pkg_status_installing
                    sb.appendRes(ctx, resource)
                    appendDots(sb)
                    return sb.toString()
                }

                if (installTask.jobReferenceForMainThread.isCancelled) {
                    val sb = StringBuilder()
                    sb.appendRes(ctx, R.string.cancelling_download)
                    appendDots(sb)
                    return sb.toString()
                }
                val taskState = installTask.state
                when (taskState) {
                    InstallTask.STATE_PENDING_DOWNLOAD, InstallTask.STATE_PENDING_INSTALL -> {
                        val sb = StringBuilder()
                        val res = if (taskState == InstallTask.STATE_PENDING_DOWNLOAD)
                            R.string.pkg_status_pending_download
                        else
                            R.string.pkg_status_pending_install
                        sb.appendRes(ctx, res)
                        appendDots(sb)
                        return sb.toString()
                    }
                }
                check(taskState == InstallTask.STATE_DOWNLOADING)
                val progress = installTask.downloadProgress.get()
                val total = installTask.downloadTotal.get()
                if (progress == total) {
                    val sb = StringBuilder()
                    sb.appendRes(ctx, R.string.pkg_status_unpacking)
                    appendDots(sb)
                    return sb.toString()
                }

                val ref = if (installTask.params.isUpdate) R.string.pkg_status_downloading_update
                    else R.string.pkg_status_downloading
                val percent = ((progress.toDouble() / total.toDouble()) * 100.0).toInt()
                return ctx.getString(ref, percent,
                    Formatter.formatShortFileSize(ctx, total)
                )
            }
        }
    }

    // Bulk updates are performed by the auto-update job and by the "Update all" button on Updates screen
    fun isEligibleForBulkUpdate(): Boolean {
        val pi = osPackageInfo
        return pi != null && (canUpdateDisabledPackages || pi.applicationInfo?.enabled == true)
                && pi.longVersionCode < rPackage.versionCode
                && !rPackage.common.optOutOfBulkUpdates
    }

    fun status(): Status {
        if (isInstalling()) {
            return Status.INSTALLING
        }

        if (rPackage.common.isSharedLibrary) {
            return Status.SHARED_LIBRARY
        }

        val pi = osPackageInfo
        if (pi == null) {
            return Status.NOT_INSTALLED
        }

        val ai = pi.applicationInfo
        if (!canUpdateDisabledPackages && ai?.enabled == false) {
            return Status.DISABLED
        }

        if (pi.longVersionCode < rPackage.versionCode) {
            return Status.OUT_OF_DATE
        }

        if (ai?.enabled == false) {
            return Status.DISABLED
        }

        return Status.UP_TO_DATE
    }

    fun preferredReleaseChannel(rPackageContainer: RPackageContainer = this.rPackage.common): ReleaseChannel {
        val group = rPackageContainer.group
        val override = if (group != null) {
            group.releaseChannelOverride
        } else {
            releaseChannelOverride
        }
        return override ?: PackageStates.defaultReleaseChannel
    }
}

class InstallerBusyException(val details: InstallerBusyError) : Exception()
