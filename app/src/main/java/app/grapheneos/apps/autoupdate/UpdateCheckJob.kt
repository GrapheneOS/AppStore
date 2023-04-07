package app.grapheneos.apps.autoupdate

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.text.format.Formatter
import android.util.Log
import androidx.navigation.NavDeepLinkBuilder
import app.grapheneos.apps.ApplicationImpl
import app.grapheneos.apps.Notifications
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.core.RPackage
import app.grapheneos.apps.core.RepoUpdateError
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.appResources
import app.grapheneos.apps.core.collectOutdatedPackageGroups
import app.grapheneos.apps.setContentTitle
import app.grapheneos.apps.show
import app.grapheneos.apps.ui.DetailsScreen
import app.grapheneos.apps.ui.ErrorDialog
import app.grapheneos.apps.ui.MainActivity
import app.grapheneos.apps.util.componentName
import app.grapheneos.apps.util.isAppInstallationAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "UpdateCheckJob"

class UpdateCheckJob : JobService() {

    override fun onStartJob(jobParams: JobParameters?): Boolean {
        ApplicationImpl.exitIfNotInitialized()
        Log.d(TAG, "onStartJob")

        if (!isAppInstallationAllowed()) {
            return false
        }

        CoroutineScope(Dispatchers.Main).launch {
            val repoUpdateError = PackageStates.requestRepoUpdate()
            if (repoUpdateError != null) {
                showUpdateCheckFailedNotification(repoUpdateError)
            } else {
                val outdatedPackageGroups = collectOutdatedPackageGroups()

                if (outdatedPackageGroups.isEmpty()) {
                    showAllUpToDateNotification()
                } else {
                    showUpdatesAvailableNotification(outdatedPackageGroups)

                    AutoUpdatePrefs.maybeScheduleAutoUpdateJob()
                }
            }

            jobFinished(jobParams, false)
            Log.d(TAG, "finished")
        }

        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // ignore the stop signal: update check is inexpensive and doesn't have relevant cancellation points
        return false
    }

    private fun showUpdatesAvailableNotification(rPackageGroups: List<List<RPackage>>) {
        val rPackages = ArrayList<RPackage>()
        rPackageGroups.forEach { it.forEach { rPackages.add(it) } }

        check(rPackages.size >= 1)

        val filteredRPackages = rPackages.filter { it.common.showAutoUpdateNotifications }

        if (filteredRPackages.isEmpty()) {
            return
        }

        val config = appResources.configuration
        val sumSize = rPackages.sumOf {
            it.collectNeededApks(config).sumOf { it.compressedSize }
        }.let {
            Formatter.formatShortFileSize(appContext, it)
        }

        Notifications.builder(Notifications.CH_AUTO_UPDATE_UPDATES_AVAILABLE).apply {
            setSmallIcon(R.drawable.ic_updates_available)

            setContentTitle(
                appResources.getQuantityString(R.plurals.notif_pkg_updates_available_title,
                rPackages.size, sumSize))
            if (filteredRPackages.size == 1) {
                val rpkg = filteredRPackages[0]
                setContentText(
                    appResources.getString(R.string.notif_pkg_update_available_text,
                    rpkg.label, rpkg.versionName))
                setContentIntent(DetailsScreen.createPendingIntent(rpkg.packageName))
            } else {
                setContentText(filteredRPackages.map { it.label }.joinToString())
                NavDeepLinkBuilder(appContext).run {
                    setGraph(R.navigation.nav_graph)
                    setDestination(R.id.updates_screen)
                    setComponentName(componentName<MainActivity>())
                    createPendingIntent()
                }.let {
                    setContentIntent(it)
                }
            }
            setStyle(Notification.BigTextStyle())
            show(Notifications.ID_AUTO_UPDATE_JOB_STATUS)
        }
    }
}

fun showAllUpToDateNotification() {
    if (PackageStates.numberOfInstallTasks() != 0) {
        return
    }

    Notifications.builder(Notifications.CH_AUTO_UPDATE_ALL_UP_TO_DATE).run {
        setSmallIcon(R.drawable.ic_check)
        setContentTitle(R.string.notif_auto_update_all_up_to_date)
        setAutoCancel(false)
        show(Notifications.ID_AUTO_UPDATE_JOB_STATUS)
    }
}

fun showUpdateCheckFailedNotification(repoUpdateError: RepoUpdateError) {
    Notifications.builder(Notifications.CH_BACKGROUND_UPDATE_CHECK_FAILED).run {
        setSmallIcon(R.drawable.ic_error)
        setContentTitle(R.string.unable_to_fetch_app_list)
        setContentIntent(ErrorDialog.createPendingIntent(repoUpdateError))
        show(Notifications.ID_AUTO_UPDATE_JOB_STATUS)
    }
}
