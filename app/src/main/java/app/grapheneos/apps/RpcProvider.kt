package app.grapheneos.apps

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.util.Log
import app.grapheneos.apps.autoupdate.AutoUpdatePrefs
import app.grapheneos.apps.core.InstallParams
import app.grapheneos.apps.core.PackageInstallerError
import app.grapheneos.apps.core.PackageState
import app.grapheneos.apps.core.startPackageInstall
import app.grapheneos.apps.core.pkgManager
import app.grapheneos.apps.util.getApplicationInfo
import app.grapheneos.apps.util.maybeGetParcelable
import app.grapheneos.apps.util.toInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RpcProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            "update_package" -> {
                val callingPackage = callingPackage!!
                val pkgName = arg!!
                val callback = extras?.maybeGetParcelable<Messenger>("callback")
                CoroutineScope(Dispatchers.Main).launch {
                    val res = runCatching {
                        updatePackage(callingPackage, pkgName)
                    }
                    res.exceptionOrNull()?.let {
                        Log.e("RpcProvider", method, it)
                    }
                    val resBool = res.getOrNull() ?: false
                    callback?.send(Message().apply { arg1 = resBool.toInt() })
                }
                return null
            }
            else -> throw IllegalArgumentException()
        }
    }

    suspend fun updatePackage(callingPackage: String, pkgName: String): Boolean {
        val TAG = "updatePackage"

        // allow preinstalled packages to trigger updates of noCode packages that explicitly allow it

        if (pkgManager.getApplicationInfo(callingPackage, 0L).flags and ApplicationInfo.FLAG_SYSTEM == 0) {
            Log.d(TAG, "$callingPackage is not preinstalled")
            return false
        }

        val pkgState = PackageStates.maybeGetPackageState(pkgName)
        if (pkgState == null) {
            Log.d(TAG, "$pkgName not found")
            return false
        }

        if (!pkgState.rPackage.common.packagesAllowedToTriggerUpdate.contains(callingPackage)) {
            Log.d(TAG, "$callingPackage is not allowed to trigger updates of $pkgName")
            return false
        }

        if (!AutoUpdatePrefs.isAllowedToAutoUpdateNoCodePackages()) {
            Log.d(TAG, "not allowed to auto-update noCode packages")
            return false
        }

        val repoUpdateError = PackageStates.requestRepoUpdateRetrying()
        val pkg = pkgState.rPackage

        if (repoUpdateError != null) {
            Log.d(TAG, "unable to update repo", repoUpdateError.throwable)
            return false;
        }

        if (pkgState.status() != PackageState.Status.OUT_OF_DATE) {
            Log.d(TAG, "$pkgName is not out-of-date")
            return false
        }

        if (pkgState.isInstalling()) {
            Log.d(TAG, "$pkgName is currently installing")
            return false
        }

        if (!pkg.common.noCode) { // enforced during installation by parsing the APK
            Log.d(TAG, "$pkgName is allowed to contain code, ignoring update request")
            return false
        }

        if (pkg.dependencies.isNotEmpty()) {
            Log.d(TAG, "$pkgName has dependencies, ignoring update request")
            return false
        }

        val installParams = InstallParams(network = null, isUpdate = true, isUserInitiated = false)

        try {
            val installError: PackageInstallerError? =
                startPackageInstall(pkg, installParams).await().await()
            return installError == null
        } catch (e: Throwable) {
            Log.d(TAG, "", e)
            return false
        }
    }

    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
