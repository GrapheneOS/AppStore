package org.grapheneos.apps.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresPermission
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.util.encoders.DecoderException
import org.grapheneos.apps.client.item.DownloadCallBack
import org.grapheneos.apps.client.item.DownloadCallBack.Companion.toUiMsg
import org.grapheneos.apps.client.item.DownloadStatus
import org.grapheneos.apps.client.item.InstallCallBack
import org.grapheneos.apps.client.item.InstallStatus
import org.grapheneos.apps.client.item.InstallStatus.Companion.createFailed
import org.grapheneos.apps.client.item.InstallStatus.Companion.createInstalling
import org.grapheneos.apps.client.item.InstallStatus.Companion.createPending
import org.grapheneos.apps.client.item.MetadataCallBack
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.item.PackageInfo.Companion.cleanCachedFiles
import org.grapheneos.apps.client.item.PackageVariant
import org.grapheneos.apps.client.item.SeamlessUpdateResponse
import org.grapheneos.apps.client.item.SessionInfo
import org.grapheneos.apps.client.item.TaskInfo
import org.grapheneos.apps.client.service.KeepAppActive
import org.grapheneos.apps.client.ui.container.MainActivity
import org.grapheneos.apps.client.ui.mainScreen.ChannelPreferenceManager
import org.grapheneos.apps.client.utils.ActivityLifeCycleHelper
import org.grapheneos.apps.client.utils.PackageManagerHelper.Companion.pmHelper
import org.grapheneos.apps.client.utils.isInstallBlockedByAdmin
import org.grapheneos.apps.client.utils.isUninstallBlockedByAdmin
import org.grapheneos.apps.client.utils.getPackageInfoCompat
import org.grapheneos.apps.client.utils.registerReceiverNotExportedCompat
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper
import org.grapheneos.apps.client.utils.network.MetaDataHelper
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr
import org.json.JSONException
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

@HiltAndroidApp
class App : Application() {

    companion object {
        const val JOB_ID_SEAMLESS_UPDATER = 1000

        const val INSTALLATION_FAILED_CHANNEL = "installationFailed"
        const val BACKGROUND_SERVICE_CHANNEL = "backgroundTask"
        const val SEAMLESS_UPDATE_FAILED_CHANNEL = "seamlessUpdateFailed"
        const val SEAMLESSLY_UPDATED_CHANNEL = "seamlesslyUpdated"
        const val ALREADY_UP_TO_DATE_CHANNEL = "alreadyUpToDate"
        const val SEAMLESS_UPDATE_INPUT_REQUIRED_CHANNEL = "seamlessUpdateInputConfirmation"

        const val SEAMLESS_UPDATE_GROUP = "seamlessUpdateGroup"
        const val INSTALLATION_FAILED_GROUP = "installationFailedGroup"

        const val DOWNLOAD_TASK_FINISHED = 1000

        @SuppressLint("StaticFieldLeak")
        // not a memory leak: this is an application Context
        lateinit var context: App

        fun getString(@StringRes id: Int): String {
            return context.getString(id)
        }

        @SuppressLint("StaticFieldLeak")
        // not a memory leak: uses an application Context
        lateinit var jobPsfsMgr: JobPsfsMgr
    }

    /*Injectable member var*/
    @Inject
    lateinit var metaDataHelper: MetaDataHelper

    @Inject
    lateinit var apkDownloadHelper: ApkDownloadHelper

    private var isActivityRunning: Activity? = null
    private var isServiceRunning = false
    private var installationCreateRequestInProgress = false
    private val isDownloadRunning = MutableLiveData<Boolean>()
    var isDownloading = false
        private set

    /*Application info object*/
    private val sessionIdsMap = mutableMapOf<Int, String>()
    private val confirmationAwaitedPackages = mutableMapOf<String, List<File>>()

    private val packagesInfo: MutableMap<String, PackageInfo> = mutableMapOf()
    private val samePackagesMap: MutableMap<String, String> = mutableMapOf()
    private val packagesMutableLiveData = MutableLiveData<Map<String, PackageInfo>>()
    val packageLiveData: LiveData<Map<String, PackageInfo>> = packagesMutableLiveData
    private val updatableAppsCount: MutableLiveData<Int> = MutableLiveData()
    val updateCount: LiveData<Int> = updatableAppsCount
    val isPrivilegeMode by lazy {
        checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
    }

    private val notificationMgr: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(this)
    }

    /*Coroutine scope and jobs var*/
    private val apkJobsMap = mutableMapOf<String, CompletableJob>()
    private val scopeApkDownload by lazy { Dispatchers.IO }
    private val scopeMetadataRefresh by lazy { Dispatchers.IO }
    private lateinit var seamlessUpdaterJob: CompletableJob
    private lateinit var refreshScope: CoroutineScope
    private var taskIdSeed = Random(SystemClock.currentThreadTimeMillis().toInt()).nextInt(1, 1000)
    private val appsChangesReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            val action = intent?.action ?: return
            val receivedPkgName = intent.data?.schemeSpecificPart ?: return
            val otherPkgName = samePackagesMap[receivedPkgName] ?: receivedPkgName
            val pkgName =
                when {
                    receivedPkgName == otherPkgName -> receivedPkgName
                    packagesInfo.containsKey(receivedPkgName)
                        && packagesInfo[receivedPkgName] != null -> receivedPkgName
                    pmHelper().isSystemApp(receivedPkgName, otherPkgName) -> otherPkgName
                    else -> receivedPkgName
                }

            val info = packagesInfo[pkgName]
            if (!packagesInfo.containsKey(pkgName) || info == null) {
                //If other package is installed or uninstalled we don't care
                return
            }

            when (action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_CHANGED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> {

                    val installStatus = info.getInstallStatusCompat(true)
                    packagesInfo[pkgName] = info.withUpdatedInstallStatus(installStatus)
                }
                else -> throw IllegalStateException("unexpected $intent")

            }
            updateLiveData()
        }
    }

    private fun updateLiveData() {

        /*copy the current (updated) value */
        val values = mutableListOf<PackageInfo>()
        values.addAll(packagesInfo.values)

        /*update live data with current value*/
        packagesMutableLiveData.postValue(packagesInfo)

        /*process current value*/
        var allTaskCompleted = true
        var foregroundServiceNeeded = false
        var updatableCount = 0

        for (packageInfo in values) {
            if (packageInfo.installStatus is InstallStatus.Updatable
                && packageInfo.downloadStatus !is DownloadStatus.Downloading
            ) {
                updatableCount++
            }
            val task = packageInfo.taskInfo

            if (task.progress == DOWNLOAD_TASK_FINISHED) {
                notificationMgr.cancel(task.id)
            } else {
                val notification = Notification.Builder(this, BACKGROUND_SERVICE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_downloading)
                    .setContentTitle(task.title)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(true)
                    .setProgress(100, task.progress, false)
                    .build()
                notification.flags = Notification.FLAG_ONGOING_EVENT
                notificationMgr.notify(task.id, notification)
                allTaskCompleted = false
                foregroundServiceNeeded = true
            }
        }

        isDownloading = !allTaskCompleted
        isDownloadRunning.postValue(isDownloading)
        updatableAppsCount.postValue(updatableCount)

        if (!isServiceRunning) {
            if (foregroundServiceNeeded && !isSeamlessUpdateRunning()) {
                startService(Intent(this@App, KeepAppActive::class.java))
            }
        }
    }

    fun installErrorResponse(error: InstallCallBack) {

        if (!error.unresolvableError) return
        val sessionId = error.sessionId
        val pkgName = sessionIdsMap[sessionId]
        sessionIdsMap.remove(sessionId)
        if (pkgName == null) return
        val info = packagesInfo[pkgName] ?: return
        val activity = isActivityRunning

        if (activity != null) {
            (activity as MainActivity).navigateToErrorScreen(error)
        } else {
            val notification = Notification.Builder(this, INSTALLATION_FAILED_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("${info.selectedVariant.appName} ${getString(R.string.installationFailed)}")
                .setContentText(error.description)
                .setShowWhen(true)
                .setAutoCancel(true)
                .build()
            notificationMgr.notify(sessionId, notification)
        }

        packagesInfo[pkgName] = info.withUpdatedInstallStatus(
            info.installStatus.createFailed(
                error.description,
                App.getString(R.string.retry)
            )
        )
        updateLiveData()
    }

    fun installSuccess(sessionId: Int) {
        sessionIdsMap.remove(sessionId)
        if (isActivityRunning != null) {
            confirmationAwaitedPackages.forEach { (packageName, apks) ->
                CoroutineScope(scopeApkDownload).launch {
                    requestInstall(apks, packageName)
                }
            }
        }
    }

    private fun refreshMetadata(): MetadataCallBack {
        try {
            val res = metaDataHelper.downloadAndVerifyMetadata { response ->
                response.packages.forEach {
                    val value = it.value
                    val pkgName = it.key
                    val channelPref = ChannelPreferenceManager
                        .getPackageChannel(this@App, pkgName)
                    val packageVariant = value.variants[channelPref]
                        ?: value.variants[App.getString(R.string.channel_default)]!!
                    val oldPkgName = packageVariant.originalPkgName
                    val shouldMapOnSamePackages = !samePackagesMap.containsKey(oldPkgName)
                            && !samePackagesMap.containsKey(pkgName)
                    if (oldPkgName != null && shouldMapOnSamePackages) {
                        samePackagesMap[oldPkgName] = pkgName
                        samePackagesMap[pkgName] = oldPkgName
                    }

                    val info = packagesInfo.getOrDefault(
                        pkgName,
                        PackageInfo(
                            pkgName = pkgName,
                            sessionInfo = SessionInfo(),
                            selectedVariant = packageVariant,
                            allVariant = value.variants.values.toList(),
                            installStatus = getInstallStatus(pkgName, packageVariant.versionCode)
                        )
                    )
                    packagesInfo[pkgName] = info.withUpdatedInstallStatus(info.getInstallStatusCompat())
                    info.cleanCachedFiles(this)
                }
            }
            updateLiveData()
            return MetadataCallBack.Success(res.timestamp)
        } catch (e: GeneralSecurityException) {
            return MetadataCallBack.SecurityError(e)
        } catch (e: JSONException) {
            return MetadataCallBack.JSONError(e)
        } catch (e: DecoderException) {
            return MetadataCallBack.DecoderError(e)
        } catch (e: UnknownHostException) {
            return MetadataCallBack.UnknownHostError(e)
        } catch (e: SSLHandshakeException) {
            return MetadataCallBack.SecurityError(e)
        } catch (e: ConnectException) {
            return MetadataCallBack.UnknownHostError(e)
        }
    }

    @RequiresPermission(Manifest.permission.INTERNET)
    fun refreshMetadata(force: Boolean = false, callback: (error: MetadataCallBack) -> Unit) {
        when {
            isMetadataSyncing() -> {
                refreshScope.cancel()
                callback.invoke(MetadataCallBack.SecurityError(Exception(
                    App.getString(R.string.refreshInProgress))))
                return
            }
            packagesInfo.isNotEmpty() && !force -> return
            isDownloading -> {
                callback.invoke(MetadataCallBack.SecurityError(Exception(
                    App.getString(R.string.pendingAppDownload))))
                return
            }
            else -> {
                refreshScope = CoroutineScope(scopeMetadataRefresh + Job())
                refreshScope.launch(Dispatchers.IO) {
                    if (packagesInfo.isNotEmpty()) packagesInfo.clear()
                    callback.invoke(refreshMetadata())
                    refreshScope.cancel()
                }
            }
        }
    }

    private fun PackageInfo.getInstallStatusCompat(isBroadcast: Boolean = false): InstallStatus {
        val fallback = selectedVariant.originalPkgName
        val versionCode = selectedVariant.versionCode
        val isSystem = pmHelper().isSystemApp(pkgName, fallback)
        val originalStatus = getInstallStatus(pkgName, versionCode, isBroadcast)
        return when {
            fallback == null || !isSystem -> originalStatus
            originalStatus.installedVersion == null -> getInstallStatus(fallback, versionCode, isBroadcast)
            else -> originalStatus
        }
    }

    private fun getInstallStatus(
        pkgName: String,
        latestVersion: Long,
        isBroadcast: Boolean = false
    ): InstallStatus {
        val pm = packageManager
        val currentInfo = packagesInfo[pkgName]
        val installedVersion = currentInfo?.installStatus?.installedVersion
        try {
            val pmInfo = pm.getPackageInfoCompat(pkgName, 0)
            val installerInfo = pm.getInstallSourceInfo(pkgName)
            val currentVersion = pmInfo.longVersionCode

            if (!pmInfo.applicationInfo.enabled) {
                return InstallStatus.Disabled(currentVersion)
            }

            if (currentVersion > latestVersion) {
                return InstallStatus.NewerVersionInstalled(currentVersion)
            }

            return if (packageName.equals(installerInfo.initiatingPackageName) || isPrivilegeMode) {
                if (currentVersion < latestVersion) {
                    InstallStatus.Updatable(currentVersion)
                } else {
                    if (isBroadcast && currentInfo != null && installedVersion == latestVersion) {
                        InstallStatus.Updated(currentVersion)
                    } else {
                        InstallStatus.Installed(currentVersion)
                    }
                }
            } else {
                InstallStatus.ReinstallRequired(currentVersion)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return InstallStatus.Installable()
        }
    }

    private suspend fun downloadPackages(
        pkgName: String,
    ): DownloadCallBack {

        if (isMetadataSyncing() || isDownloadJobRunning(pkgName)) {
            return DownloadCallBack.Canceled()
        }

        val scope = scopeApkDownload.createJobForPackageDownload(pkgName)
        val resultTask: Deferred<DownloadCallBack> =
            withContext(scope) {
                return@withContext async {
                    taskIdSeed++
                    val taskId = taskIdSeed
                    packagesInfo[pkgName] =
                        packagesInfo[pkgName]!!.withUpdatedDownloadStatus(
                            DownloadStatus.Downloading(
                                App.getString(R.string.processing),
                                0,
                                0,
                                0.0,
                                false
                            )
                        ).withUpdatedTask(
                            TaskInfo(
                                taskId,
                                App.getString(R.string.startingDownload),
                                0
                            )
                        )
                    updateLiveData()

                    val variant = packagesInfo[pkgName]!!.selectedVariant
                    val taskCompleted = TaskInfo(taskId, "", DOWNLOAD_TASK_FINISHED)
                    val result = apkDownloadHelper.downloadAndVerifySHA256(pkgName, variant)
                    { read: Long, total: Long, doneInPercent: Double, completed: Boolean ->
                        if (doneInPercent == -1.0) return@downloadAndVerifySHA256
                        packagesInfo[pkgName] =
                            packagesInfo[pkgName]!!.withUpdatedDownloadStatus(
                                DownloadStatus.Downloading(
                                    downloadSize = total.toInt(),
                                    downloadedSize = read.toInt(),
                                    downloadedPercent = doneInPercent,
                                    completed = completed
                                )
                            ).withUpdatedTask(
                                TaskInfo(
                                    taskId,
                                    "${variant.appName} ${getString(R.string.is_being_downloaded)} ...",
                                    doneInPercent.toInt()
                                )
                            )
                        updateLiveData()
                    }

                    when (result) {
                        is DownloadCallBack.Success, is DownloadCallBack.Canceled -> {
                            packagesInfo[pkgName] =
                                packagesInfo[pkgName]!!.withUpdatedDownloadStatus(null)
                                    .withUpdatedTask(taskCompleted)
                        }
                        else -> {
                            packagesInfo[pkgName] =
                                packagesInfo[pkgName]!!.withUpdatedDownloadStatus(
                                    DownloadStatus.Failed(result.error?.localizedMessage ?: "")
                                ).withUpdatedTask(taskCompleted)
                        }
                    }
                    updateLiveData()
                    result
                }
            }

        val result = resultTask.await()
        scope.job.markAsCompleted(pkgName)
        return result
    }

    private fun downloadAndInstallPackages(
        pkgName: String,
        callback: (error: DownloadCallBack) -> Unit
    ) {
        val variant = packagesInfo[pkgName]?.selectedVariant ?: return
        if (!areDependenciesInstalled(pkgName)) {
            val packages = mutableListOf<String>()
            packages.add(pkgName)
            val dependencies = variant.includeAllDependency()
            if (dependencies.isNotEmpty()) {
                packages.addAll(0, variant.includeAllDependency())
            }
            return downloadMultipleApps(packages, callback, true)
        }
        CoroutineScope(scopeApkDownload).launch(Dispatchers.IO) {
            val result = downloadPackages(pkgName)
            if (result !is DownloadCallBack.Success) {
                callback.invoke(result)
            }
            if (result is DownloadCallBack.Success) {
                val apks = result.apks
                if (apks.isNotEmpty()) {
                    requestInstall(apks, pkgName)
                }
            }
        }
    }

    private fun PackageVariant.includeAllDependency(): List<String> {

        val result = mutableListOf<String>()
        this.dependencies.forEach { name ->

            val dependency = this@App.packagesInfo[name]
            dependency?.let {
                val downloadStatus = it.downloadStatus
                val isDownloading = downloadStatus != null && downloadStatus.isDownloading
                if (dependency.installStatus !is InstallStatus.Installed &&
                    dependency.installStatus !is InstallStatus.Updated &&
                    dependency.installStatus !is InstallStatus.Installing &&
                    dependency.installStatus !is InstallStatus.Pending &&
                    !isDownloading
                ) {
                    result.add(0, name)
                }
                /*dependency can have it's own dependency and that should be installed before installing this one*/
                val subDependency = dependency.selectedVariant.includeAllDependency()
                if (subDependency.isNotEmpty()) {
                    result.addAll(0, subDependency)
                }
            }
        }
        //clean duplicate item and return the unique dependency
        return result.distinct().toList()
    }

    private fun downloadMultipleApps(
        appsToDownload: List<String>,
        callback: (result: DownloadCallBack) -> Unit,
        shouldAllSucceed: Boolean = false,
    ) {
        CoroutineScope(scopeApkDownload).launch(Dispatchers.IO) {
            val deferredDownloads = this.async {
                val downloadResults = mutableListOf<Deferred<DownloadCallBack>>()
                appsToDownload.forEach { pkgName ->
                    val deferredResult = this.async {
                        if (isDownloadJobRunning(pkgName)) {
                            throw IllegalStateException("download get called while a download task is already running")
                        }
                        val result = downloadPackages(pkgName)
                        callback.invoke(result)
                        val currentInfo = packagesInfo[pkgName]!!
                        packagesInfo[pkgName] = currentInfo.withUpdatedInstallStatus(
                            currentInfo.installStatus.createPending()
                        )
                        result
                    }
                    downloadResults.add(deferredResult)
                }
                downloadResults
            }
            val results = deferredDownloads.await().awaitAll()
            var shouldProceed = results.size == appsToDownload.size
            for (pkgName in appsToDownload) {
                val result = results[appsToDownload.indexOf(pkgName)]
                when {
                    result is DownloadCallBack.Success && shouldProceed -> {
                        val apks = result.apks
                        val isInstalled = installApps(apks, pkgName)
                        shouldProceed = (!shouldAllSucceed || isInstalled)
                    }
                    result !is DownloadCallBack.Success || !shouldProceed -> {
                        packagesInfo[pkgName]?.let { info ->
                            packagesInfo[pkgName] = info.withUpdatedInstallStatus(
                                info.getInstallStatusCompat()
                            )
                            shouldProceed = !shouldAllSucceed
                            updateLiveData()
                        }
                    }
                }
            }
        }
    }

    private fun CoroutineDispatcher.createJobForPackageDownload(packageName: String): CoroutineContext {
        if (isDownloadJobRunning(packageName)) {
            apkJobsMap[packageName]?.cancelChildren()
            apkJobsMap[packageName]?.cancel()
        }
        val job = Job()
        apkJobsMap[packageName] = job
        return this + job + Dispatchers.IO
    }

    private fun isDownloadJobRunning(packageName: String): Boolean {
        val packageSpecificJob = apkJobsMap[packageName]
        return ((packageSpecificJob != null && packageSpecificJob.isActive
                && !packageSpecificJob.isCompleted && !packageSpecificJob.isCancelled))
    }

    private fun Job.markAsCompleted(packageName: String) {
        apkJobsMap.remove(packageName)
        (this as CompletableJob).complete()
    }

    fun updateServiceStatus(isRunning: Boolean) {
        isServiceRunning = isRunning
    }

    private suspend fun requestInstall(
        apks: List<File>,
        pkgName: String,
        backgroundMode: Boolean = false
    ): Boolean {
        var installed = false
        if ((isPrivilegeMode && jobPsfsMgr.autoInstallEnabled()) || backgroundMode || ((isActivityRunning != null && sessionIdsMap.isEmpty() && !installationCreateRequestInProgress))) {
            installationCreateRequestInProgress = true
            installed = installApps(apks, pkgName)
            confirmationAwaitedPackages.remove(pkgName)
            installationCreateRequestInProgress = false
        } else {
            confirmationAwaitedPackages[pkgName] = apks
        }
        return installed
    }

    private suspend fun installApps(
        apks: List<File>,
        pkgName: String
    ): Boolean {
        val maxInstallTime = 5 * 60 * 1000L //Max wait for 5 min
        var pkgInfo: PackageInfo
        var sessionId = 0

        return try {
            return withTimeout(maxInstallTime) {
                sessionId = this@App.pmHelper().install(apks)
                pkgInfo = packagesInfo[pkgName]!!

                sessionIdsMap[sessionId] = pkgName
                packagesInfo[pkgName] = pkgInfo
                    .withUpdatedSession(
                        SessionInfo(sessionId, true)
                    ).withUpdatedInstallStatus(
                        pkgInfo.installStatus.createInstalling(
                            isInstalling = true,
                            canCancelTask = false
                        )
                    )
                updateLiveData()

                while (sessionIdsMap.containsKey(sessionId)) {
                    delay(1000)
                }
                pkgInfo = packagesInfo[pkgName]!!
                val result =
                    pkgInfo.installStatus is InstallStatus.Updated || pkgInfo.installStatus is InstallStatus.Installed
                if (result) {
                    pkgInfo.cleanCachedFiles(this@App)
                }
                result
            }
        } catch (e: TimeoutCancellationException) {
            pkgInfo = packagesInfo[pkgName]!!
            packagesInfo[pkgName] = packagesInfo[pkgName]!!.withUpdatedInstallStatus(
                pkgInfo.installStatus.createFailed(e.localizedMessage ?: "")
            ).withUpdatedSession(SessionInfo(sessionId, false))
            this@App.pmHelper().abandonSession(sessionId)
            false
        }
    }

    fun openAppDetails(pkgName: String) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", pkgName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun uninstallPackage(pkgName: String) {
        if (!isUninstallBlockedByAdmin()) {
            pmHelper().uninstall(pkgName)
        }
    }

    private tailrec fun openApp(pkgName: String, callback: (result: String) -> Unit): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkgName)
        if (intent == null) {
            val otherPkgName = samePackagesMap[pkgName]
            if (otherPkgName != null && pmHelper().isSystemApp(otherPkgName)) {
                return openApp(otherPkgName, callback)
            }
            callback.invoke(getString(R.string.unOpenable))
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    fun handleOnVariantChange(
        packageName: String,
        channel: String
    ) {
        val infoToCheck = packagesInfo[packageName] ?: return
        ChannelPreferenceManager.savePackageChannel(
            this,
            packageName,
            channel
        )
        var packageVariant = infoToCheck.selectedVariant
        infoToCheck.allVariant.forEach {
            if (it.type == channel) {
                packageVariant = it
            }
        }
        val installStatus = infoToCheck.getInstallStatusCompat()
        packagesInfo[packageName] = infoToCheck.withUpdatedVariant(packageVariant)
            .withUpdatedInstallStatus(installStatus)
        updateLiveData()
    }

    fun areDependenciesInstalled(pkgName: String): Boolean {
        packagesInfo[pkgName]?.selectedVariant?.dependencies?.forEach {
            val status = packagesInfo[it]
            if (status == null || status.installStatus.installedVersion == null) {
                return false
            }
        }
        return true
    }

    fun handleOnClick(
        pkgName: String,
        callback: (result: String) -> Unit
    ) {
        val status = packagesInfo[pkgName]?.installStatus
        val variant = packagesInfo[pkgName]?.selectedVariant

        if (status == null || variant == null) {
            callback.invoke(getString(R.string.syncUnfinished))
            return
        }

        if ((!isPrivilegeMode || !jobPsfsMgr.autoInstallEnabled()) && !canRequestPackageInstalls()) {
            callback.invoke(getString(R.string.allowUnknownSources))
            return
        }

        if (isInstallBlockedByAdmin()) {
            callback.invoke(getString(R.string.icBlocked))
            return
        }

        when (status) {
            is InstallStatus.Installable,
            is InstallStatus.Updatable,
            is InstallStatus.ReinstallRequired,
            is InstallStatus.Failed -> {
                CoroutineScope(scopeApkDownload).launch(Dispatchers.IO) {
                    downloadAndInstallPackages(pkgName)
                    { error -> callback.invoke(error.toUiMsg()) }
                }
            }
            is InstallStatus.Installed,
            is InstallStatus.NewerVersionInstalled,
            is InstallStatus.Updated -> {
                openApp(pkgName, callback)
            }
            is InstallStatus.Disabled -> {
                openAppDetails(pkgName)
            }
            is InstallStatus.Installing -> {
                callback.invoke(getString(R.string.installationInProgress))
            }
            is InstallStatus.Uninstalling -> {
                callback.invoke(getString(R.string.uninstallationInProgress))
            }
            is InstallStatus.Pending -> {
                callback.invoke(getString(R.string.otherAppsDownloadInProgress))
            }
        }
    }

    fun updateAllUpdatableApps(callback: (result: String) -> Unit) {
        if ((!isPrivilegeMode || !jobPsfsMgr.autoInstallEnabled()) && !canRequestPackageInstalls()) {
            callback.invoke(getString(R.string.allowUnknownSources))
            return
        }

        if (isInstallBlockedByAdmin()) {
            callback.invoke(getString(R.string.icBlocked))
            return
        }

        val appsToUpdate = mutableListOf<String>()
        packagesInfo.values.forEach { info ->
            val installStatus = info.installStatus

            if (installStatus is InstallStatus.Updatable &&
                info.downloadStatus !is DownloadStatus.Downloading
            ) {
                appsToUpdate.add(info.pkgName)
            }
        }
        downloadMultipleApps(appsToUpdate, {
            callback.invoke(it.toUiMsg())
        })
    }

    private fun canRequestPackageInstalls(): Boolean {
        if (!packageManager.canRequestPackageInstalls()) {
            isActivityRunning?.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(
                    Uri.parse(String.format("package:%s", packageName))
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        return true
    }

    private fun isSeamlessUpdateRunning() =
        this::seamlessUpdaterJob.isInitialized
                && seamlessUpdaterJob.isActive
                && !seamlessUpdaterJob.isCompleted && !seamlessUpdaterJob.isCancelled

    // If emergencyPackage is non-null, update check affects only that package and updates to it
    // are installed automatically regardless of auto update settings
    fun seamlesslyUpdateApps(emergencyPackage: String?, onFinished: (result: SeamlessUpdateResponse) -> Unit) {
        if (isInstallBlockedByAdmin()) {
            onFinished.invoke(SeamlessUpdateResponse())
            return
        }
        if (isActivityRunning != null || isDownloading) {
            // don't auto update if app is in foreground
            onFinished.invoke(SeamlessUpdateResponse())
            return
        }

        if (isSeamlessUpdateRunning()) {
            return
        }
        seamlessUpdaterJob = Job()

        if (packagesInfo.isNotEmpty()) {
            packagesInfo.clear()
        }

        if (isMetadataSyncing()) return

        refreshScope = CoroutineScope(scopeMetadataRefresh + Job())

        CoroutineScope(seamlessUpdaterJob + Dispatchers.IO).launch {

            val metaData = refreshMetadata()
            if (!metaData.isSuccessFull) {
                //sync failed, will try again
                onFinished.invoke(SeamlessUpdateResponse())
                return@launch
            }

            val updatedPackages = mutableListOf<String>()
            val updateFailedPackages = mutableListOf<String>()
            val requireConfirmationPackages = mutableListOf<String>()

            val isAutoInstallEnabled = jobPsfsMgr.autoInstallEnabled() || emergencyPackage != null

            packagesInfo.values.forEach { info ->
                if (emergencyPackage != null && info.pkgName != emergencyPackage) {
                    return@forEach
                }

                val installStatus = info.installStatus

                if (installStatus is InstallStatus.Disabled) {
                    return@forEach
                }

                val variant = info.selectedVariant
                val installedVersion = installStatus.installedVersion
                val isInstalled = installedVersion != null
                val isUpdatable = installedVersion == null || installedVersion < variant.versionCode

                if (installStatus is InstallStatus.Updatable || (isPrivilegeMode && isInstalled && isUpdatable)) {
                    if (isDownloadJobRunning(info.pkgName)) {
                        throw IllegalStateException("download get called while a download task is already running")
                    }
                    val downloadResult = downloadPackages(info.pkgName)
                    if (downloadResult is DownloadCallBack.Success) {
                        if (isAutoInstallEnabled) {
                            if (installApps(downloadResult.apks, info.pkgName)) {
                                updatedPackages.add(variant.appName)
                            } else {
                                updateFailedPackages.add(variant.appName)
                            }
                        } else {
                            requireConfirmationPackages.add(variant.appName)
                        }
                    } else {
                        updateFailedPackages.add(variant.appName)
                    }
                }
            }

            onFinished.invoke(
                SeamlessUpdateResponse(
                    updatedPackages,
                    updateFailedPackages,
                    requireConfirmationPackages,
                    true
                )
            )

            refreshScope.cancel()
            seamlessUpdaterJob.complete()
        }
    }

    private fun isMetadataSyncing(): Boolean = this::refreshScope.isInitialized && refreshScope.isActive

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

        createNotificationChannel()

        val appsChangesFilter = IntentFilter()
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_CHANGED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        appsChangesFilter.addDataScheme("package")

        registerReceiverNotExportedCompat(appsChangesReceiver, appsChangesFilter)

        registerActivityLifecycleCallbacks(ActivityLifeCycleHelper { activity ->
            isActivityRunning = activity
            if ((!isPrivilegeMode || !jobPsfsMgr.autoInstallEnabled()) && isActivityRunning != null) {
                confirmationAwaitedPackages.forEach { (packageName, apks) ->
                    CoroutineScope(scopeApkDownload).launch {
                        requestInstall(apks, packageName)
                    }
                }
            }
        })

        context = this

        jobPsfsMgr = JobPsfsMgr(this)
        jobPsfsMgr.initialize()
    }

    private fun createNotificationChannel() {

        val seamlessUpdateGroup = NotificationChannelGroup(
            SEAMLESS_UPDATE_GROUP,
            getString(R.string.suGroupName)
        )
        seamlessUpdateGroup.description = getString(R.string.suGroupDescription)
        val installationFailedGroup = NotificationChannelGroup(
            INSTALLATION_FAILED_GROUP,
            getString(R.string.installationFailed)
        )
        listOf(
            seamlessUpdateGroup,
            installationFailedGroup
        ).forEach {
            notificationMgr.createNotificationChannelGroup(it)
        }

        val installationFailed = NotificationChannelCompat.Builder(
            INSTALLATION_FAILED_CHANNEL,
            NotificationManager.IMPORTANCE_HIGH
        ).setName(getString(R.string.installationFailed))
            .setVibrationEnabled(false)
            .setGroup(INSTALLATION_FAILED_GROUP)
            .setShowBadge(false)
            .setLightsEnabled(false)
            .build()

        val channelSeamlesslyUpdated = NotificationChannelCompat.Builder(
            SEAMLESSLY_UPDATED_CHANNEL,
            NotificationManager.IMPORTANCE_LOW
        ).setName(getString(R.string.nUpdatedTitle))
            .setDescription(getString(R.string.nUpdatedDescription))
            .setVibrationEnabled(false)
            .setGroup(SEAMLESS_UPDATE_GROUP)
            .setShowBadge(false)
            .setLightsEnabled(false)
            .build()

        val channelConformationNeeded = NotificationChannelCompat.Builder(
            SEAMLESS_UPDATE_INPUT_REQUIRED_CHANNEL,
            NotificationManager.IMPORTANCE_HIGH
        ).setName(getString(R.string.nUpdateAvailableTitle))
            .setDescription(getString(R.string.nUpdateAvailableDescription))
            .setVibrationEnabled(false)
            .setGroup(SEAMLESS_UPDATE_GROUP)
            .setShowBadge(false)
            .setLightsEnabled(false)
            .build()

        val channelAlreadyUpToDate = NotificationChannelCompat.Builder(
            ALREADY_UP_TO_DATE_CHANNEL,
            NotificationManager.IMPORTANCE_MIN
        ).setName(getString(R.string.nUpToDateTitle))
            .setDescription(getString(R.string.nUpToDateDescription))
            .setVibrationEnabled(false)
            .setGroup(SEAMLESS_UPDATE_GROUP)
            .setShowBadge(false)
            .setLightsEnabled(false)
            .build()

        val channelSeamlessUpdateFailed = NotificationChannelCompat.Builder(
            SEAMLESS_UPDATE_FAILED_CHANNEL,
            NotificationManager.IMPORTANCE_DEFAULT
        ).setName(getString(R.string.nUpdatesFailedTitle))
            .setDescription(getString(R.string.nUpdatesFailedDescription))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .setGroup(SEAMLESS_UPDATE_GROUP)
            .setLightsEnabled(false)
            .build()

        val channelBackgroundTask = NotificationChannelCompat.Builder(
            BACKGROUND_SERVICE_CHANNEL,
            NotificationManager.IMPORTANCE_LOW
        ).setName(getString(R.string.nBackgroundTaskTitle))
            .setDescription(getString(R.string.nBackgroundTaskDescription))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .setLightsEnabled(false)
            .build()

        listOf(
            installationFailed,
            channelBackgroundTask,
            channelSeamlesslyUpdated,
            channelConformationNeeded,
            channelAlreadyUpToDate,
            channelSeamlessUpdateFailed
        ).forEach {
            notificationMgr.createNotificationChannel(it)
        }
    }

    fun isActivityRunning() = isActivityRunning != null
    fun isDownloadRunning(): LiveData<Boolean> = isDownloadRunning
    fun isSyncingSuccessful() = !isMetadataSyncing() && packagesInfo.isNotEmpty()

}
