package org.grapheneos.apps.client.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.item.InstallCallBack

class APKInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return

        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -999)
        val app = context.applicationContext as App
        val unknownCode = -999

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, unknownCode)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = intent.getParcelableIntentExtra(Intent.EXTRA_INTENT)
                confirmationIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    (context).startActivity(it)
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                app.installErrorResponse(InstallCallBack.FailureAborted(sessionId))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                app.installSuccess(sessionId)
            }
            PackageInstaller.STATUS_FAILURE -> {
                app.installErrorResponse(InstallCallBack.Failure(sessionId))
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                app.installErrorResponse(InstallCallBack.FailureStorage(sessionId))
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                app.installErrorResponse(InstallCallBack.FailureInvalid(sessionId))
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                app.installErrorResponse(InstallCallBack.Incompatible(sessionId))
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                app.installErrorResponse(InstallCallBack.Conflict(sessionId))
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                app.installErrorResponse(InstallCallBack.Blocked(sessionId))
            }
            unknownCode -> {
                //ignore it
            }
            else -> {
                app.installErrorResponse(InstallCallBack.Failure(sessionId))
            }
        }
    }
}