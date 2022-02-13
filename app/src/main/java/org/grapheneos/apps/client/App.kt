package org.grapheneos.apps.client

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.util.encoders.DecoderException
import org.grapheneos.apps.client.item.DownloadCallBack
import org.grapheneos.apps.client.item.DownloadCallBack.Companion.toUiMsg
import org.grapheneos.apps.client.item.DownloadStatus
import org.grapheneos.apps.client.item.InstallStatus
import org.grapheneos.apps.client.item.InstallStatus.Companion.createFailed
import org.grapheneos.apps.client.item.MetadataCallBack
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.item.PackageVariant
import org.grapheneos.apps.client.item.SeamlessUpdateResponse
import org.grapheneos.apps.client.item.SessionInfo
import org.grapheneos.apps.client.item.TaskInfo
import org.grapheneos.apps.client.service.KeepAppActive
import org.grapheneos.apps.client.service.SeamlessUpdaterJob
import org.grapheneos.apps.client.ui.mainScreen.ChannelPreferenceManager
import org.grapheneos.apps.client.utils.ActivityLifeCycleHelper
import org.grapheneos.apps.client.utils.PackageManagerHelper.Companion.pmHelper
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper
import org.grapheneos.apps.client.utils.network.MetaDataHelper
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr
import org.json.JSONException
import java.io.File
import java.lang.ref.WeakReference
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

@HiltAndroidApp
class App : Application() {

    companion object {
        const val BACKGROUND_SERVICE_CHANNEL = "backgroundTask"
        const val SEAMLESS_UPDATE_FAILED_CHANNEL = "seamlessUpdateFailed"
        const val SEAMLESSLY_UPDATED_CHANNEL = "seamlesslyUpdated"
        const val ALREADY_UP_TO_DATE_CHANNEL = "alreadyUpToDate"
        const val SEAMLESS_UPDATE_INPUT_REQUIRED_CHANNEL = "seamlessUpdateInputConfirmation"

        const val SEAMLESS_UPDATE_GROUP = "seamlessUpdateGroup"

        const val DOWNLOAD_TASK_FINISHED = 1000
        private lateinit var context: WeakReference<Context>

        private const val SEAMLESS_UPDATER_JOB_ID = 1000

        fun getString(@StringRes id: Int): String {
            return context.get()!!.getString(id)
        }
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

    /*Application info object*/
    private val sessionIdsMap = mutableMapOf<Int, String>()
    private val confirmationAwaitedPackages = mutableMapOf<String, List<File>>()

    private val packagesInfo: MutableMap<String, PackageInfo> = mutableMapOf()
    private val packagesMutableLiveData = MutableLiveData<Map<String, PackageInfo>>()
    val packageLiveData: LiveData<Map<String, PackageInfo>> = packagesMutableLiveData

    private val jobPsfsMgr by lazy {
        JobPsfsMgr(this)
    }
    private val notificationMgr: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(this)
    }


    /*Coroutine scope and jobs var*/
    private val apkJobsMap = mutableMapOf<String, CompletableJob>()
    private val scopeApkDownload by lazy { Dispatchers.IO }
    private val scopeMetadataRefresh by lazy { Dispatchers.IO }
    private lateinit var seamlessUpdaterJob: CompletableJob
    private lateinit var refreshJob: CompletableJob
    private var taskIdSeed = Random(SystemClock.currentThreadTimeMillis().toInt()).nextInt(1, 1000)
    private val appsChangesReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            val action = intent?.action ?: return
            val pkgName = intent.data?.schemeSpecificPart ?: return

            val installedVersion = try {
                val appInfo = packageManager.getPackageInfo(
                    pkgName,
                    PackageManager.GET_META_DATA
                )
                appInfo.longVersionCode
            } catch (e: PackageManager.NameNotFoundException) {
                -1L
            }

            val info = packagesInfo[pkgName]
            if (!packagesInfo.containsKey(pkgName) || info == null) {
                //If other package is installed or uninstalled we don't care
                return
            }
            val latestVersion = info.selectedVariant.versionCode.toLong()

            when (action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    packagesInfo[pkgName] = info.withUpdatedInstallStatus(
                        InstallStatus.Installed(installedVersion, latestVersion)
                    )
                }
                Intent.ACTION_PACKAGE_REPLACED -> {
                    packagesInfo[pkgName] = info.withUpdatedInstallStatus(
                        InstallStatus.Updated(installedVersion, latestVersion)
                    )
                }
                Intent.ACTION_PACKAGE_FULLY_REMOVED,
                Intent.ACTION_PACKAGE_REMOVED -> {
                    packagesInfo[pkgName] = info.withUpdatedInstallStatus(
                        InstallStatus.Installable(latestVersion)
                    )
                }
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

        for (packageInfo in values) {
            val task = packageInfo.taskInfo

            if (task.progress == DOWNLOAD_TASK_FINISHED) {
                notificationMgr.cancel(task.id)
            } else {
                val notification = Notification.Builder(this, BACKGROUND_SERVICE_CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(task.title)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, task.progress, false)
                    .build()
                notification.flags = Notification.FLAG_ONGOING_EVENT
                notificationMgr.notify(task.id, notification)
                allTaskCompleted = false
                foregroundServiceNeeded = true
            }
        }

        isDownloadRunning.postValue(allTaskCompleted)

        if (!isServiceRunning) {
            if (foregroundServiceNeeded && !isSeamlessUpdateRunning()) {
                startService(
                    Intent(
                        this@App,
                        KeepAppActive::class.java
                    )
                )
            }
        }
    }

    fun installIntentResponse(sessionId: Int, errorMsg: String, userDeclined: Boolean = false) {
        val pkgName = sessionIdsMap[sessionId]
        sessionIdsMap.remove(sessionId)
        if (pkgName == null) return
        val info = packagesInfo[pkgName] ?: return
        packagesInfo[pkgName] = info.withUpdatedInstallStatus(
            info.installStatus.createFailed(
                errorMsg,
                if (userDeclined) App.getString(R.string.denied) else null
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
                    val pkgName = value.packageName
                    val channelPref = ChannelPreferenceManager
                        .getPackageChannel(this@App, pkgName)
                    val channelVariant = value.variants[channelPref]
                        ?: value.variants[App.getString(R.string.channel_default)]!!
                    val installStatus = getInstalledStatus(
                        pkgName,
                        channelVariant.versionCode.toLong()
                    )

                    val info = packagesInfo.getOrDefault(
                        pkgName,
                        PackageInfo(
                            id = pkgName,
                            sessionInfo = SessionInfo(),
                            selectedVariant = channelVariant,
                            allVariant = value.variants.values.toList(),
                            installStatus = installStatus
                        )
                    )
                    packagesInfo[pkgName] = info.withUpdatedInstallStatus(installStatus)
                }
            }
            res.cleanOldFiles(this)
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
        }
    }

    @RequiresPermission(Manifest.permission.INTERNET)
    fun refreshMetadata(force: Boolean = false, callback: (error: MetadataCallBack) -> Unit) {
        if ((packagesInfo.isNotEmpty() && !force) ||
            (this::refreshJob.isInitialized && refreshJob.isActive
                    && !refreshJob.isCompleted && !refreshJob.isCancelled)
        ) {
            return
        }

        refreshJob = Job()
        CoroutineScope(scopeMetadataRefresh + refreshJob).launch(Dispatchers.IO) {
            callback.invoke(refreshMetadata())
            refreshJob.complete()
        }
    }

    private fun getInstalledStatus(pkgName: String, latestVersion: Long): InstallStatus {
        val pm = packageManager
        return try {
            val appInfo = pm.getPackageInfo(pkgName, 0)
            val installerInfo = pm.getInstallSourceInfo(pkgName)
            val currentVersion = appInfo.longVersionCode

            if (packageName.equals(installerInfo.initiatingPackageName)) {
                if (currentVersion < latestVersion) {
                    InstallStatus.Updatable(currentVersion, latestVersion)
                } else {
                    InstallStatus.Installed(currentVersion, latestVersion)
                }
            } else {
                InstallStatus.ReinstallRequired(currentVersion, latestVersion)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return InstallStatus.Installable(latestVersion)
        }
    }


    private suspend fun downloadPackages(
        variant: PackageVariant,
    ): DownloadCallBack {

        if (isDownloadJobRunning(variant.pkgName)) {
            return DownloadCallBack.Canceled()
        }

        val scope = scopeApkDownload.createJobForPackageDownload(variant.pkgName)
        val resultTask: Deferred<DownloadCallBack> =
            withContext(scope) {
                return@withContext async {
                    taskIdSeed++
                    val taskId = taskIdSeed
                    packagesInfo[variant.pkgName] =
                        packagesInfo[variant.pkgName]!!.withUpdatedDownloadStatus(
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

                    val taskCompleted = TaskInfo(taskId, "", DOWNLOAD_TASK_FINISHED)
                    val result = apkDownloadHelper.downloadAndVerifySHA256(variant = variant)
                    { read: Long, total: Long, doneInPercent: Double, completed: Boolean ->
                        if (doneInPercent == -1.0) return@downloadAndVerifySHA256
                        packagesInfo[variant.pkgName] =
                            packagesInfo[variant.pkgName]!!.withUpdatedDownloadStatus(
                                DownloadStatus.Downloading(
                                    downloadSize = total.toInt(),
                                    downloadedSize = read.toInt(),
                                    downloadedPercent = doneInPercent,
                                    completed = completed
                                )
                            ).withUpdatedTask(
                                TaskInfo(
                                    taskId,
                                    "${getString(R.string.downloading)} ${variant.appName} ...",
                                    doneInPercent.toInt()
                                )
                            )
                        updateLiveData()
                    }

                    when (result) {
                        is DownloadCallBack.Success, is DownloadCallBack.Canceled -> {
                            packagesInfo[variant.pkgName] =
                                packagesInfo[variant.pkgName]!!.withUpdatedDownloadStatus(null)
                                    .withUpdatedTask(taskCompleted)
                        }
                        else -> {
                            packagesInfo[variant.pkgName] =
                                packagesInfo[variant.pkgName]!!.withUpdatedDownloadStatus(
                                    DownloadStatus.Failed(result.error?.localizedMessage ?: "")
                                ).withUpdatedTask(taskCompleted)
                        }
                    }
                    updateLiveData()
                    result
                }
            }

        val result = resultTask.await()
        scope.job.markAsCompleted(variant.pkgName)
        return result
    }

    private fun downloadAndInstallPackages(
        variant: PackageVariant,
        callback: (error: DownloadCallBack) -> Unit
    ) {

        if (!isDependenciesInstalled(variant)) {
            val packages = mutableListOf<PackageVariant>()
            packages.add(variant)
            val dependencies = variant.includeAllDependency()
            if (dependencies.isNotEmpty()) {
                packages.addAll(0, variant.includeAllDependency())
            }
            return downloadMultipleApps(packages, callback, true)
        }
        CoroutineScope(scopeApkDownload).launch(Dispatchers.IO) {
            val result = downloadPackages(variant)
            if (result !is DownloadCallBack.Success) {
                callback.invoke(result)
            }
            if (result is DownloadCallBack.Success) {
                val apks = result.apks
                if (apks.isNotEmpty()) {
                    requestInstall(apks, variant.pkgName)
                }
            }
        }
    }

    private fun PackageVariant.includeAllDependency(): List<PackageVariant> {

        val result = mutableListOf<PackageVariant>()
        this.dependencies.forEach { name ->

            val dependency = this@App.packagesInfo[name]
            dependency?.let {
                result.add(0, dependency.selectedVariant)
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
        appsToDownload: List<PackageVariant>,
        callback: (result: DownloadCallBack) -> Unit,
        shouldAllSucceed: Boolean = false,
    ) {
        CoroutineScope(scopeApkDownload).launch(Dispatchers.IO) {
            val deferredDownloads = this.async {
                val downloadResults = mutableListOf<Deferred<DownloadCallBack>>()
                appsToDownload.forEach { variant ->
                    val deferredResult = this.async {
                        if (isDownloadJobRunning(variant.pkgName)) {
                            throw IllegalStateException("download get called while a download task is already running")
                        }
                        val result = downloadPackages(variant)
                        callback.invoke(result)
                        packagesInfo[variant.pkgName] = packagesInfo[variant.pkgName]!!
                            .withUpdatedInstallStatus(
                                InstallStatus.Pending(variant.versionCode.toLong())
                            )
                        result
                    }
                    downloadResults.add(deferredResult)
                }
                downloadResults
            }
            val results = deferredDownloads.await().awaitAll()
            var shouldProceed = results.size == appsToDownload.size
            for (variant in appsToDownload) {
                val pkgName = variant.pkgName
                val result = results[appsToDownload.indexOf(variant)]
                when {
                    result is DownloadCallBack.Success && shouldProceed -> {
                        val apks = result.apks
                        val isInstalled = installApps(apks, pkgName)
                        shouldProceed = (!shouldAllSucceed || isInstalled)
                    }
                    result !is DownloadCallBack.Success || !shouldProceed -> {
                        packagesInfo[pkgName] =
                            packagesInfo[pkgName]!!.withUpdatedInstallStatus(
                                getInstalledStatus(pkgName, variant.versionCode.toLong())
                            )
                        shouldProceed = !shouldAllSucceed
                        updateLiveData()
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
    ) {
        if (backgroundMode || (isActivityRunning != null && sessionIdsMap.isEmpty() && !installationCreateRequestInProgress)) {
            installationCreateRequestInProgress = true
            installApps(apks, pkgName)
            confirmationAwaitedPackages.remove(pkgName)
            installationCreateRequestInProgress = false
        } else {
            confirmationAwaitedPackages[pkgName] = apks
        }
    }

    private suspend fun installApps(
        apks: List<File>,
        pkgName: String
    ): Boolean {
        val maxInstallTime = 5 * 60 * 1000L //Max wait for 5 min
        var pkgInfo: PackageInfo

        return try {
            return withTimeout(maxInstallTime) {
                val sessionId = this@App.pmHelper().install(apks)
                pkgInfo = packagesInfo[pkgName]!!

                sessionIdsMap[sessionId] = pkgName
                packagesInfo[pkgName] = pkgInfo
                    .withUpdatedSession(
                        SessionInfo(sessionId, true)
                    ).withUpdatedInstallStatus(
                        InstallStatus.Installing(
                            true,
                            pkgInfo.selectedVariant.versionCode.toLong(),
                            true
                        )
                    )
                updateLiveData()

                while (sessionIdsMap.containsKey(sessionId)) {
                    delay(1000)
                }
                pkgInfo = packagesInfo[pkgName]!!
                pkgInfo.installStatus is InstallStatus.Updated || pkgInfo.installStatus is InstallStatus.Installed
            }
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    fun openAppDetails(pkgName: String) {
        isActivityRunning?.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse(String.format("package:%s", pkgName)))
                .addCategory(Intent.CATEGORY_DEFAULT), null
        )
    }

    fun uninstallPackage(pkgName: String, callback: (result: String) -> Unit) {
        callback.invoke("${getString(R.string.uninstalling)} $pkgName")
        pmHelper().uninstall(pkgName)
    }

    fun handleOnVariantChange(
        packageName: String,
        channel: String,
        callback: (info: PackageInfo) -> Unit
    ) {
        val infoToCheck = packagesInfo[packageName] ?: return
        val channelVariants = infoToCheck.allVariant
        var channelVariant: PackageVariant = infoToCheck.selectedVariant
        channelVariants.forEach { packageVariant ->
            if (packageVariant.type == channel) {
                channelVariant = packageVariant
            }
        }
        val installStatus = getInstalledStatus(packageName, channelVariant.versionCode.toLong())
        packagesInfo[packageName] = infoToCheck.withUpdatedVariant(channelVariant)
            .withUpdatedInstallStatus(installStatus)
        callback.invoke(packagesInfo[packageName]!!)
    }

    fun isDependenciesInstalled(pkgName: String) =
        isDependenciesInstalled(packagesInfo[pkgName]?.selectedVariant)


    private fun isDependenciesInstalled(variant: PackageVariant?): Boolean {
        var result = true
        variant?.dependencies?.forEach {
            val status = packagesInfo[it]
            if (status == null || (status.installStatus !is InstallStatus.Installed ||
                        status.installStatus !is InstallStatus.Updatable ||
                        status.installStatus !is InstallStatus.Updated)
            ) {
                result = false
            }
        }
        return result
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

        if (!isRequestInstallPackagesGranted()) {
            callback.invoke(getString(R.string.allowUnknownSources))
            return
        }

        CoroutineScope(scopeApkDownload).launch(Dispatchers.IO) {
            downloadAndInstallPackages(variant) {
                callback.invoke(it.toUiMsg())
            }
            when (status) {
                is InstallStatus.Installable -> {
                    downloadAndInstallPackages(variant)
                    { error -> callback.invoke(error.genericMsg) }
                }
                is InstallStatus.Installed -> {
                    callback.invoke(getString(R.string.reinstalling))
                    downloadAndInstallPackages(variant)
                    { error -> callback.invoke(error.toUiMsg()) }
                }
                is InstallStatus.Installing -> {
                    callback.invoke(getString(R.string.installationInProgress))
                }
                is InstallStatus.Uninstalling -> {
                    callback.invoke(getString(R.string.uninstallationInProgress))
                }
                is InstallStatus.Updated -> {
                    callback.invoke(getString(R.string.alreadyUpToDate))
                }
                is InstallStatus.Updatable -> {
                    callback.invoke("${getString(R.string.updating)} $pkgName")
                    downloadAndInstallPackages(variant)
                    { error -> callback.invoke(error.toUiMsg()) }
                }
                is InstallStatus.ReinstallRequired -> {
                    downloadAndInstallPackages(variant)
                    { error -> callback.invoke(error.toUiMsg()) }
                }
                is InstallStatus.Failed -> {
                    callback.invoke(getString(R.string.reinstalling))
                    downloadAndInstallPackages(variant)
                    { error -> callback.invoke(error.toUiMsg()) }
                }
                is InstallStatus.Pending -> {
                    /* stub! */
                }
            }
        }
    }

    fun updateAllUpdatableApps(callback: (result: String) -> Unit) {
        if (!isRequestInstallPackagesGranted()) {
            callback.invoke(getString(R.string.allowUnknownSources))
            return
        }

        val appsToUpdate = mutableListOf<PackageVariant>()
        packagesInfo.values.forEach { info ->
            val installStatus = info.installStatus
            val variant = info.selectedVariant

            if (installStatus is InstallStatus.Updatable &&
                info.downloadStatus !is DownloadStatus.Downloading
            ) {
                appsToUpdate.add(variant)
            }
        }
        downloadMultipleApps(appsToUpdate, {
            callback.invoke(it.toUiMsg())
        })
    }

    private fun isRequestInstallPackagesGranted(): Boolean {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, getString(R.string.allowUnknownSources), Toast.LENGTH_SHORT).show()
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

    fun seamlesslyUpdateApps(onFinished: (result: SeamlessUpdateResponse) -> Unit) {
        if (isActivityRunning != null) {
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

        if (this::refreshJob.isInitialized && refreshJob.isActive
            && !refreshJob.isCompleted && !refreshJob.isCancelled
        ) {
            refreshJob.cancel()
        }

        refreshJob = Job()

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

            val isAutoInstallEnabled = jobPsfsMgr.autoInstallEnabled()

            packagesInfo.forEach { info ->
                val installStatus = info.value.installStatus
                val variant = info.value.selectedVariant

                if (installStatus is InstallStatus.Updatable) {
                    if (isDownloadJobRunning(variant.pkgName)) {
                        throw IllegalStateException("download get called while a download task is already running")
                    }
                    val downloadResult = downloadPackages(variant)
                    if (downloadResult is DownloadCallBack.Success) {
                        if (isAutoInstallEnabled) {
                            if (installApps(downloadResult.apks, variant.pkgName)) {
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

            refreshJob.complete()
            seamlessUpdaterJob.complete()
        }
    }

    private fun cancelScheduleAutoUpdate() {
        val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancel(SEAMLESS_UPDATER_JOB_ID)
    }

    override fun onTerminate() {
        super.onTerminate()
        unregisterReceiver(appsChangesReceiver)
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

        createNotificationChannel()

        val appsChangesFilter = IntentFilter()
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        appsChangesFilter.addDataScheme("package")

        registerReceiver(appsChangesReceiver, appsChangesFilter)

        registerActivityLifecycleCallbacks(ActivityLifeCycleHelper { activity ->
            isActivityRunning = activity
            if (isActivityRunning != null) {
                confirmationAwaitedPackages.forEach { (packageName, apks) ->
                    CoroutineScope(scopeApkDownload).launch {
                        requestInstall(apks, packageName)
                    }
                }
            }
        })

        context = WeakReference(this)

        jobPsfsMgr.onJobPsfsChanged { isEnabled, networkType, time ->
            if (isEnabled) {
                val jobInfo = JobInfo.Builder(
                    SEAMLESS_UPDATER_JOB_ID,
                    ComponentName(this, SeamlessUpdaterJob::class.java)
                ).setRequiredNetworkType(networkType)
                    .setPersisted(true)
                    .setPeriodic(time)
                    .build()

                val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                jobScheduler.schedule(jobInfo)
            } else {
                cancelScheduleAutoUpdate()
            }
        }

    }

    private fun createNotificationChannel() {

        val seamlessUpdateGroup = NotificationChannelGroup(
            SEAMLESS_UPDATE_GROUP,
            getString(R.string.suGroupName)
        )
        seamlessUpdateGroup.description = getString(R.string.suGroupDescription)
        notificationMgr.createNotificationChannelGroup(seamlessUpdateGroup)

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

}
