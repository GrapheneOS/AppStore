package org.grapheneos.apps.client.service

import android.app.job.JobParameters
import android.app.job.JobService

class SeamlessUpdaterJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}