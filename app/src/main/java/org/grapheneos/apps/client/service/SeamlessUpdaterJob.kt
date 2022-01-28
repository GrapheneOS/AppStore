package org.grapheneos.apps.client.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.ui.container.MainActivity

class SeamlessUpdaterJob : JobService() {

    companion object {
        const val REQUEST_CODE = 100
        const val NOTIFICATION_ID = 10001
        const val NOTIFICATION_ACTION = "OpenViaNotification"
    }

    private fun List<String>.valuesAsString(): String {
        var result = ""
        val isMultiple = size > 1
        for (i in 0 until size) {
            result += if (i != (size - 1) && isMultiple) {
                "${get(i)}, "
            } else {
                "${get(i)} "
            }
        }
        return result
    }

    override fun onStartJob(params: JobParameters?): Boolean {

        val app = (this as Context).applicationContext as App
        if (app.isActivityRunning()) return true

        val action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.app_info),
            App.getString(R.string.openApp),
            PendingIntent.getActivity(
                this,
                REQUEST_CODE,
                Intent(this, MainActivity::class.java).setAction(NOTIFICATION_ACTION),
                PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

        val notification = Notification.Builder(this, App.SEAMLESS_UPDATE_SUCCESS_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(action)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)

        app.seamlesslyUpdateApps { result ->

            if (result.executedSuccessfully) {
                val updated = result.updatedSuccessfully.valuesAsString()
                val failed = result.failedToUpdate.valuesAsString()
                val requireConfirmation = result.requireConfirmation.valuesAsString()

                var content = ""
                if (updated.isNotBlank() && result.updatedSuccessfully.isNotEmpty()) {
                    content += "$updated has been successfully updated"
                }

                if (failed.isNotBlank() && result.failedToUpdate.isNotEmpty()) {
                    if (content.isNotBlank()) content += ", "
                    content += "$failed has failed to update " + if (requireConfirmation.isNotEmpty()) "" else "."
                }

                if (requireConfirmation.isNotBlank() && result.requireConfirmation.isNotEmpty()) {
                    if (content.isNotBlank()) content += ", "
                    content += "$requireConfirmation: update available."
                }

                notification.setContentText(content)
                    .setContentTitle(
                        if (updated.isNotEmpty() || failed.isNotEmpty() || requireConfirmation.isNotEmpty()) "Seamless update result"
                        else App.getString(R.string.alreadyUpToDate)
                    )

            } else {
                notification.setChannelId(App.SEAMLESS_UPDATE_FAILED_CHANNEL)
                notification.setContentTitle(App.getString(R.string.seamlessUpdatesCheckFailed))
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification.build())

            jobFinished(params, true)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}