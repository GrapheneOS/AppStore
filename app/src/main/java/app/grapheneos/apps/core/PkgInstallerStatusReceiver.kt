package app.grapheneos.apps.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.util.SparseArray
import app.grapheneos.apps.Notifications
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.autoupdate.showAllUpToDateNotification
import app.grapheneos.apps.setContentTitle
import app.grapheneos.apps.show
import app.grapheneos.apps.ui.DetailsScreen
import app.grapheneos.apps.ui.ErrorDialog
import app.grapheneos.apps.util.ActivityUtils
import app.grapheneos.apps.util.getNumber
import app.grapheneos.apps.util.getParcelableOrThrow
import app.grapheneos.apps.util.intent
import kotlinx.parcelize.Parcelize
import app.grapheneos.apps.util.PendingActivityIntent
import app.grapheneos.apps.util.className
import app.grapheneos.apps.util.getParcelable2
import app.grapheneos.apps.util.maybeGetNumber
import app.grapheneos.apps.util.putParcelable2
import kotlinx.coroutines.channels.Channel
import java.util.UUID

class PkgInstallerStatusReceiver: BroadcastReceiver() {

    companion object {
        const val TAG = "PkgInstallerStatusReceiver"

        val EXTRA_REQUEST_INFO = className<PkgInstallerStatusReceiver>() + ".EXTRA_REQUEST_INFOS"

        private val sessionCompletionChannels = SparseArray<Channel<PackageInstallerError?>>()

        fun getCompletionChannelForSession(sessionId: Int): Channel<PackageInstallerError?> {
            val ch = Channel<PackageInstallerError?>(1)
            synchronized(sessionCompletionChannels) {
                check(!sessionCompletionChannels.contains(sessionId))
                sessionCompletionChannels[sessionId] = ch
            }
            return ch
        }

        fun getIntentSender(rPackages: List<RPackage>, isUserInitiated: Boolean) =
            getIntentSenderInner(
                InstallerRequestInfo(rPackages.map { InstallerPackageInfo(it) }.toTypedArray(),
                    isUserInitiated))

        fun getIntentSenderForUninstall(pkgName: String, pkgLabel: String, versionName: String) =
            getIntentSenderInner(
                InstallerRequestInfo(arrayOf(InstallerPackageInfo(pkgName, pkgLabel, versionName)),
                    isUserInitiated = true, isUninstall = true))

        private fun getIntentSenderInner(requestInfo: InstallerRequestInfo): IntentSender {
            val intent = intent<PkgInstallerStatusReceiver>().apply {
                identifier = UUID.randomUUID().toString()
                replaceExtras(Bundle().apply { putParcelable2(EXTRA_REQUEST_INFO, requestInfo) })
            }
            return PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_MUTABLE).intentSender
        }
    }

    override fun onReceive(brContext: Context, intent: Intent) {
        val extras = intent.getExtras()!!

        val request = extras.getParcelable2<InstallerRequestInfo>(EXTRA_REQUEST_INFO)
        val sessionId = extras.maybeGetNumber<Int>(PackageInstaller.EXTRA_SESSION_ID)
        // PackageInstaller.EXTRA_PACKAGE_NAME is useless: it doesn't support multi-packages sessions
        // and is set to manifest package name even if package was renamed via original-package

        val coarseStatus = extras.getNumber<Int>(PackageInstaller.EXTRA_STATUS)

        if (coarseStatus == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val puaIntent = extras.getParcelableOrThrow<Intent>(Intent.EXTRA_INTENT)

            if (request.isUninstall) {
                ActivityUtils.mostRecentResumedActivity()?.startActivity(puaIntent)
                return
            }

            val pkgState = if (request.packages.size == 1) {
                PackageStates.maybeGetPackageState(request.packages.first().pkgName)
            } else {
                // multi-package session
                InstallerSessions.installerSessionMap[sessionId!!]
            } ?: return

            Log.d(TAG, "pending user action for session $sessionId, packageName ${pkgState.pkgName}, " +
                    "status ${pkgState.status()}")

            if (isPrivilegedInstaller) {
                // bypass the DISALLOW_INSTALL_UNKNOWN_SOURCES user restriction
                puaIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }

            if (pkgState.status() != PackageState.Status.INSTALLING) {
                return
            }

            check(!pkgState.waitingForPendingUserAction)
            pkgState.waitingForPendingUserAction = true
            pkgState.notifyListeners()

            val notifChannel = if (request.isUserInitiated)
                Notifications.CH_CONFIRMATION_REQUIRED
            else Notifications.CH_AUTO_UPDATE_CONFIRMATION_REQUIRED

            ActivityUtils.addPendingAction(
                // Session that is in "pending user action" state is hard to track across app
                // processes, recreating the session if process dies is simpler and more reliable,
                // hence the "transient" flag
                PendingActivityIntent(puaIntent).apply { transient = true }
            ) {
                Notifications.builder(notifChannel).apply {
                    setSmallIcon(R.drawable.ic_pending)
                    setContentTitle(R.string.notif_title_pending_user_action)

                    // don't use label from pkgState, it may have changed since the session was created
                    val pkg = request.packages.find { it.pkgName == pkgState.pkgName }!!

                    val textId = if (pkgState.osPackageInfo != null)
                        R.string.notif_text_pending_user_action_update
                    else R.string.notif_text_pending_user_action_install

                    setContentText(appResources.getString(textId, pkg.label, pkg.versionName))

                    val pi = DetailsScreen.createPendingIntent(request.packages.last().pkgName)
                    setContentIntent(pi)
                }
            }
            return
        }

        val completionChannel = sessionId?.let {
            synchronized(sessionCompletionChannels) {
                val res = sessionCompletionChannels[it]
                sessionCompletionChannels.remove(it)
                res
            }
        }

        if (coarseStatus == PackageInstaller.STATUS_SUCCESS) {
            if (completionChannel != null) {
                check(completionChannel.trySend(null).isSuccess)
            }

            if (collectOutdatedPackageGroups().isEmpty()) {
                showAllUpToDateNotification()
            }

            if (request.isUserInitiated) {
                return
            }

            for (pkg in request.packages) {
                if (!pkg.shouldShowAutoUpdateNotification) {
                    continue
                }

                Notifications.builder(Notifications.CH_AUTO_UPDATE_UPDATED_PACKAGES).run {
                    setSmallIcon(R.drawable.ic_check)
                    setContentTitle(R.string.notif_auto_update_updated_package_title)
                    setContentText(appResources.getString(R.string.notif_auto_update_updated_package_text, pkg.label, pkg.versionName))
                    setContentIntent(DetailsScreen.createPendingIntent(pkg.pkgName))
                    show(Notifications.generateId())
                }
            }

            return
        }

        // PackageInstaller.EXTRA_LEGACY_STATUS is much more detailed than EXTRA_STATUS, see
        // PackageManager.installStatusToPublicStatus() and deleteStatusToPublicStatus()
        val status = extras.getNumber<Int>("android.content.pm.extra.LEGACY_STATUS")
        val message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)!!
        // PackageInstaller.EXTRA_OTHER_PACKAGE_NAME is not needed, it's set only for
        // INSTALL_FAILED_DUPLICATE_PERMISSION error (as of Android 13 QPR1) and is included in
        // stringified form in EXTRA_STATUS_MESSAGE

        val pie = PackageInstallerError(request, status, message, coarseStatus)

        if (completionChannel != null) {
            check(completionChannel.trySend(pie).isSuccess)
        }

        if (coarseStatus == PackageInstaller.STATUS_FAILURE_ABORTED) {
            return
        }

        val template = if (request.isUninstall) UninstallError(pie) else InstallError(pie)
        val pendingDialog = ErrorDialog.createPendingDialog(template)

        val notifChannel = if (request.isUserInitiated || request.isUninstall)
            Notifications.CH_INSTALLATION_FAILED
        else
            Notifications.CH_AUTO_UPDATE_FAILED

        ActivityUtils.addPendingAction(pendingDialog) {
            Notifications.builder(notifChannel).apply {
                setSmallIcon(R.drawable.ic_error)
                setContentTitle(if (request.isUninstall) R.string.uninstall_failed else R.string.notif_installation_failed)
                setContentText(request.packages.map { it.label }.joinToString())
                setContentIntent(DetailsScreen.createPendingIntent(request.packages.last().pkgName))
            }
        }
    }
}

@Parcelize
class InstallerPackageInfo(
    val pkgName: String,
    val label: String,
    val versionName: String,
    val shouldShowAutoUpdateNotification: Boolean = true,
) : Parcelable {
    constructor(rPackage: RPackage) : this(rPackage.packageName, rPackage.label,
        rPackage.versionName, rPackage.common.showAutoUpdateNotifications)
}

@Parcelize
class InstallerRequestInfo(
    val packages: Array<InstallerPackageInfo>,
    val isUserInitiated: Boolean,
    val isUninstall: Boolean = false,
) : Parcelable

@Parcelize
class PackageInstallerError(
    val request: InstallerRequestInfo,
    val status: Int,
    val message: String,
    val coarseStatus: Int,
) : Parcelable
