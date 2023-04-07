package app.grapheneos.apps.autoupdate

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.getSystemService
import app.grapheneos.apps.ApplicationImpl.Companion.JOB_SCHEDULER_JOB_ID_AUTO_UPDATE
import app.grapheneos.apps.ApplicationImpl.Companion.JOB_SCHEDULER_JOB_ID_UPDATE_CHECK
import app.grapheneos.apps.R
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.appResources
import app.grapheneos.apps.util.componentName
import app.grapheneos.apps.util.getSharedPreferences

private const val TAG = "AutoUpdatePrefs"

object AutoUpdatePrefs {
    val KEY_BG_UPDATE_CHECK_ENABLED = appResources.getString(R.string.pref_key_background_update_check)
    val KEY_JOB_REPEAT_INTERVAL = appResources.getString(R.string.pref_key_background_update_check_interval)
    val KEY_PKG_AUTO_UPDATE_ENABLED = appResources.getString(R.string.pref_key_auto_update_packages)
    val KEY_JOB_NETWORK_TYPE = appResources.getString(R.string.pref_key_network_type_for_auto_update_job)

    private val jobScheduler: JobScheduler = appContext.getSystemService()!!
    private val prefs = getSharedPreferences(R.string.pref_file_settings)

    private fun isBackgroundUpdateCheckEnabled() = prefs.getBoolean(
        KEY_BG_UPDATE_CHECK_ENABLED, appResources.getBoolean(R.bool.pref_def_background_update_check))

    private fun isPackageAutoUpdateEnabled() = prefs.getBoolean(
        KEY_PKG_AUTO_UPDATE_ENABLED, appResources.getBoolean(R.bool.pref_def_auto_update_packages))

    private fun getAutoUpdateJobNetworkTypePref(): Int =
        prefs.getString(KEY_JOB_NETWORK_TYPE, null)?.toInt() ?:
        appResources.getString(R.string.pref_def_network_type_for_bg_update_job).toInt()

    private fun getAutoUpdateJobNetworkType(): Int {
        return when (getAutoUpdateJobNetworkTypePref()) {
            2 -> JobInfo.NETWORK_TYPE_UNMETERED
            3 -> JobInfo.NETWORK_TYPE_NOT_ROAMING
            else -> JobInfo.NETWORK_TYPE_ANY
        }
    }

    private fun getUpdateCheckIntervalMillis() =
        prefs.getString(KEY_JOB_REPEAT_INTERVAL, null)?.toLong() ?:
        appResources.getString(R.string.pref_def_background_update_check_interval).toLong()

    fun isAllowedToAutoUpdateNoCodePackages(): Boolean {
        return isPackageAutoUpdateEnabled() || prefs.getBoolean(
            appResources.getString(R.string.pref_key_always_allow_nocode_updates),
            appResources.getBoolean(R.bool.pref_def_always_allow_noCode_updates))
    }

    // Auto update is implemented in the following way:
    // There are 2 jobs: update check job and auto update job.
    //
    // Update check job runs periodically, its only constraint is the presence of network connection.
    // If update check job determines that there are available updates, then it shows the "updates
    // available" notification and schedules the auto update job.
    //
    // Auto update job is allowed to run only when the device is idle, to avoid updating packages
    // that are in use, and to not take up network bandwidth that is more likely to be scarce when
    // the device is being used. Auto update job supports network type constraints.
    //
    // Both of the jobs can be disabled by the user.

    fun setupJobs() {
        updateJobs()
        // note that listener must be strongly referenced to work
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsChangeListener)
    }

    val sharedPrefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener  { _, key ->
        if (key == KEY_BG_UPDATE_CHECK_ENABLED || key == KEY_JOB_NETWORK_TYPE || key == KEY_JOB_REPEAT_INTERVAL) {
            updateJobs()
        }
    }

    private fun updateJobs() {
        val pendingJobs = jobScheduler.getAllPendingJobs()

        val pendingUpdateCheckJob = pendingJobs.find { it.id == JOB_SCHEDULER_JOB_ID_UPDATE_CHECK }
        val pendingAutoUpdateJob = pendingJobs.find { it.id == JOB_SCHEDULER_JOB_ID_AUTO_UPDATE }

        if (!isBackgroundUpdateCheckEnabled()) {
            if (pendingUpdateCheckJob != null) {
                jobScheduler.cancel(JOB_SCHEDULER_JOB_ID_UPDATE_CHECK)
                Log.d(TAG, "update check job cancelled: $pendingUpdateCheckJob")
            }

            if (pendingAutoUpdateJob != null) {
                jobScheduler.cancel(JOB_SCHEDULER_JOB_ID_AUTO_UPDATE)
                Log.d(TAG, "auto-update job cancelled: $pendingAutoUpdateJob")
            }

            return
        }

        if (pendingAutoUpdateJob?.isPeriodic() == true) {
            // this is a leftover job from a previous app version
            jobScheduler.cancel(JOB_SCHEDULER_JOB_ID_AUTO_UPDATE)
        }

        scheduleUpdateCheckJob(pendingUpdateCheckJob)
    }

    private fun scheduleUpdateCheckJob(pendingJob: JobInfo?) {
        val repeatInterval = getUpdateCheckIntervalMillis()

        if (pendingJob != null
            && pendingJob.intervalMillis == repeatInterval
            && pendingJob.service.className == UpdateCheckJob::class.java.name
        ) {
            return
        }

        val jobInfo = JobInfo.Builder(JOB_SCHEDULER_JOB_ID_UPDATE_CHECK, componentName<UpdateCheckJob>()).run {
            setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            setPersisted(true)
            setPeriodic(repeatInterval)
            build()
        }

        val scheduleRes = jobScheduler.schedule(jobInfo)
        if (scheduleRes == JobScheduler.RESULT_SUCCESS) {
            Log.d(TAG, "update check job scheduled, interval: ${jobInfo.intervalMillis}")
        } else {
            Log.d(TAG, "unable to schedule update check job, schedule result: $scheduleRes")
        }
    }

    fun maybeScheduleAutoUpdateJob() {
        if (!isPackageAutoUpdateEnabled()) {
            return
        }

        val jobInfo = JobInfo.Builder(JOB_SCHEDULER_JOB_ID_AUTO_UPDATE, componentName<AutoUpdateJob>()).run {
            setRequiredNetworkType(getAutoUpdateJobNetworkType())
            // As of Android 13, "device is idle" is defined in the following way:
            // - screen is off (or device is docked) for 31 minutes (+ 5 minutes slop)
            // - device does not have an active UI projection (eg Android Auto)
            //
            // Package is force-stopped when installation starts and remains unusable for the whole duration of
            // installation, which can take over a minute on GrapheneOS due to the full AOT compilation.
            setRequiresDeviceIdle(true)
            setPersisted(true)
            build()
        }

        val scheduleRes = jobScheduler.schedule(jobInfo)
        if (scheduleRes == JobScheduler.RESULT_SUCCESS) {
            Log.d(TAG, "auto update job scheduled")
        } else {
            Log.d(TAG, "unable to schedule auto update job, schedule result: $scheduleRes")
        }
    }
}
