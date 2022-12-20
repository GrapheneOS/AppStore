package app.grapheneos.apps.autoupdate

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.util.Log
import app.grapheneos.apps.Notifications
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.core.updateAllPackages
import app.grapheneos.apps.setContentTitle
import app.grapheneos.apps.show
import app.grapheneos.apps.ui.ErrorDialog
import app.grapheneos.apps.util.ActivityUtils
import app.grapheneos.apps.util.checkMainThread
import app.grapheneos.apps.util.isAppInstallationAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

private const val TAG = "AutoUpdateJob"

class AutoUpdateJob : JobService() {
    private var activeJobs: List<Job>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        Log.d(TAG, "onStartJob")

        checkMainThread()

        if (ActivityUtils.mostRecentResumedActivity() != null) {
            Log.d(TAG, "activity is running, skipping the job")
            return false
        }

        if (!isAppInstallationAllowed()) {
            return false
        }

        CoroutineScope(Dispatchers.Main).launch {
            val repoUpdateError = PackageStates.requestRepoUpdate()

            if (repoUpdateError == null) {
                val jobs = updateAllPackages(isUserInitiated = false)
                if (jobs.isNotEmpty()) {
                    activeJobs = jobs
                    // Wait for packages to be committed, but don't wait for installation to complete.
                    // OS will continue installing the committed packages even if app process dies,
                    // and will spawn it automatically when session completes (by sending a broadcast
                    // to PkgInstallerStatusReceiver)
                    jobs.joinAll()

                    launch {
                        try {
                            val allSuccessful = jobs.awaitAll().awaitAll().none { installError -> installError != null }
                            // note that this statement may not be reached if our process is killed
                            // before installation fully completes
                            if (allSuccessful) {
                                showAllUpToDateNotification()
                            }
                        } catch (ignored: Throwable) {}
                    }

                    activeJobs = null
                }
            } else {
                Notifications.builder(Notifications.CH_AUTO_UPDATE_FAILED).run {
                    setSmallIcon(R.drawable.ic_error)
                    setContentTitle(R.string.unable_to_fetch_app_list)
                    setContentIntent(ErrorDialog.createPendingIntent(repoUpdateError))
                    show(Notifications.ID_AUTO_UPDATE_JOB_STATUS)
                }
            }

            jobFinished(params, false)
            Log.d(TAG, "job finished")
        }

        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        checkMainThread()

        // There are many reasons the job might be stopped, important one is that network type
        // might have changed from what is specified in job parameters

        Log.d(TAG, "onStopJob, reason ${if (Build.VERSION.SDK_INT >= 31) params.stopReason else 0}")

        val jobs = activeJobs
        activeJobs = null
        if (jobs != null) {
            jobs.forEach { it.cancel() }
            // "true" means "reschedule the job again as soon as possible"
            return true
        } else {
            return false
        }
    }

    companion object {
        fun showAllUpToDateNotification() {
            Notifications.builder(Notifications.CH_AUTO_UPDATE_ALL_UP_TO_DATE).run {
                setSmallIcon(R.drawable.ic_check)
                setContentTitle(R.string.notif_auto_update_all_up_to_date)
                setAutoCancel(false)
                show(Notifications.ID_AUTO_UPDATE_JOB_STATUS)
            }
        }
    }
}
