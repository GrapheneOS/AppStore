package app.grapheneos.apps.autoupdate

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.getSystemService
import app.grapheneos.apps.ApplicationImpl.Companion.JOB_SCHEDULER_JOB_ID_AUTO_UPDATE
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

    fun isBackgroundUpdateCheckEnabled() = prefs.getBoolean(
        KEY_BG_UPDATE_CHECK_ENABLED, appResources.getBoolean(R.bool.pref_def_background_update_check))

    fun isPackageAutoUpdateEnabled() = prefs.getBoolean(
        KEY_PKG_AUTO_UPDATE_ENABLED, appResources.getBoolean(R.bool.pref_def_auto_update_packages))

    private fun jobNetworkType(): Int {
        return when (bgUpdateJobNetworkType()) {
            2 -> JobInfo.NETWORK_TYPE_UNMETERED
            3 -> JobInfo.NETWORK_TYPE_NOT_ROAMING
            else -> JobInfo.NETWORK_TYPE_ANY
        }
    }

    private fun jobRepeatIntervalMillis() =
        prefs.getString(KEY_JOB_REPEAT_INTERVAL, null)?.toLong() ?:
        appResources.getString(R.string.pref_def_background_update_check_interval).toLong()

    private fun bgUpdateJobNetworkType(): Int =
        prefs.getString(KEY_JOB_NETWORK_TYPE, null)?.toInt() ?:
        appResources.getString(R.string.pref_def_network_type_for_bg_update_job).toInt()

    fun isAllowedToAutoUpdateNoCodePackages(): Boolean {
        return isPackageAutoUpdateEnabled() || prefs.getBoolean(
            appResources.getString(R.string.pref_key_always_allow_nocode_updates),
            appResources.getBoolean(R.bool.pref_def_always_allow_noCode_updates))
    }

    fun setupJob() {
        updateJob()
        // note that listener must be strongly referenced to work
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsChangeListener)
    }

    val sharedPrefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener  { _, key ->
        if (key == KEY_BG_UPDATE_CHECK_ENABLED || key == KEY_JOB_NETWORK_TYPE || key == KEY_JOB_REPEAT_INTERVAL) {
            updateJob()
        }
    }

    private fun updateJob() {
        val pendingJob = jobScheduler.getPendingJob(JOB_SCHEDULER_JOB_ID_AUTO_UPDATE)

        if (!isBackgroundUpdateCheckEnabled()) {
            if (pendingJob != null) {
                jobScheduler.cancel(JOB_SCHEDULER_JOB_ID_AUTO_UPDATE)
                Log.d(TAG, "job cancelled")
            }
            return
        }

        val networkType = jobNetworkType()
        val repeatInterval = jobRepeatIntervalMillis()

        // no way to read back the network type without the deprecated getNetworkType() method
        @Suppress("DEPRECATION")
        if (pendingJob != null
            && pendingJob.networkType == networkType
            && pendingJob.intervalMillis == repeatInterval
            && pendingJob.service.className == AutoUpdateJob::class.java.name
        ) {
            return
        }

        val jobInfo = JobInfo.Builder(JOB_SCHEDULER_JOB_ID_AUTO_UPDATE, componentName<AutoUpdateJob>()).run {
            setRequiredNetworkType(jobNetworkType())
            setPersisted(true)
            setPeriodic(repeatInterval)
            setRequiresDeviceIdle(true)
            build()
        }

        jobScheduler.schedule(jobInfo)
        Log.d(TAG, "job scheduled, interval: ${jobInfo.intervalMillis}")
    }
}
