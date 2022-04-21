package org.grapheneos.apps.client.utils.sharedPsfsMgr

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.service.SeamlessUpdaterJob

class JobPsfsMgr(val context: Context) {

    companion object {
        val AUTO_UPDATE_PREFERENCE = App.getString(R.string.autoUpdatePreferenceKey)
        val AUTO_INSTALL_KEY = App.getString(R.string.seamlessInstallEnabled)
        val BACKGROUND_UPDATE_KEY = App.getString(R.string.backgroundUpdatedEnabled)

        val NETWORK_TYPE_KEY = App.getString(R.string.networkType)
        val RESCHEDULE_TIME_KEY = App.getString(R.string.rescheduleTiming)
    }

    private val jobScheduler = context.getSystemService(JobScheduler::class.java)

    private val sharedPrefs = context.getSharedPreferences(AUTO_UPDATE_PREFERENCE, MODE_PRIVATE)

    private fun backgroundUpdateEnabled() = sharedPrefs.getBoolean(
        BACKGROUND_UPDATE_KEY,
        context.resources.getBoolean(R.bool.background_update_default)
    )

    private fun jobNetworkType(): Int {
        return when (networkType()) {
            2 -> JobInfo.NETWORK_TYPE_UNMETERED
            3 -> JobInfo.NETWORK_TYPE_NOT_ROAMING
            else -> JobInfo.NETWORK_TYPE_ANY
        }
    }

    private val defaultRescheduleTiming =
        context.resources.getString(R.string.reschedule_timing_default).toLong()

    private fun jobRepeatIntervalMillis() = sharedPrefs.getString(
        RESCHEDULE_TIME_KEY,
        defaultRescheduleTiming.toString()
    )?.toLongOrNull() ?: defaultRescheduleTiming

    private fun networkType(): Int = sharedPrefs.getString(
        NETWORK_TYPE_KEY,
        context.resources.getString(R.string.network_type_default)
    )!!.toInt()

    fun initialize() {
        if (jobScheduler.getPendingJob(App.JOB_ID_SEAMLESS_UPDATER) == null) {
            updateJob()
        }

        sharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsChangeListener)
    }

    val sharedPrefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener  { _, key ->
        if (key == BACKGROUND_UPDATE_KEY || key == NETWORK_TYPE_KEY || key == RESCHEDULE_TIME_KEY) {
            updateJob()
        }
    }

    fun updateJob() {
        if (!backgroundUpdateEnabled()) {
            jobScheduler.cancel(App.JOB_ID_SEAMLESS_UPDATER)
            return
        }

        val jobInfo = JobInfo.Builder(
            App.JOB_ID_SEAMLESS_UPDATER,
            ComponentName(context, SeamlessUpdaterJob::class.java)
        ).setRequiredNetworkType(jobNetworkType())
            .setPersisted(true)
            .setPeriodic(jobRepeatIntervalMillis())
            .build()

        jobScheduler.schedule(jobInfo)
    }

    fun autoInstallEnabled() = sharedPrefs.getBoolean(
        AUTO_INSTALL_KEY,
        context.resources.getBoolean(R.bool.background_update_default)
    )
}
