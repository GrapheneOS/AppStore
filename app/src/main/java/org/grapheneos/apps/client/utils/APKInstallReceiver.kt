package org.grapheneos.apps.client.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

class APKInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return

        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -999)
        val app = context.applicationContext as App

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmationIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    (context).startActivity(it)
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                app.installIntentResponse(sessionId, App.getString(R.string.aborted), true)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                //View Model have active listener for ACTION_PACKAGE_ADDED
                app.installSuccess(sessionId)
            }
            PackageInstaller.STATUS_FAILURE -> {
                app.installIntentResponse(sessionId, App.getString(R.string.failed))
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                app.installIntentResponse(sessionId, App.getString(R.string.storageFailure))
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                app.installIntentResponse(sessionId, App.getString(R.string.invalidFailure))
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                app.installIntentResponse(sessionId, App.getString(R.string.incompatibleApp))
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                app.installIntentResponse(
                    sessionId,
                    App.getString(R.string.conflictingPackagesError)
                )
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                app.installIntentResponse(sessionId, App.getString(R.string.installationBlocked))
            }
            else -> {
                app.installIntentResponse(sessionId, App.getString(R.string.unknownError))
            }
        }
    }
}