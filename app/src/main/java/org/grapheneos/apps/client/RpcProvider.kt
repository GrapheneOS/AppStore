package org.grapheneos.apps.client

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlin.IllegalArgumentException

class RpcProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            "gmscompat_config_update_check" -> {
                gmsCompatConfigUpdateCheck()
                return null
            }
            else -> throw IllegalArgumentException()
        }
    }

    fun gmsCompatConfigUpdateCheck() {
        val context = context!!
        val pm = context.packageManager
        val callerInfo = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(callingPackage!!, ApplicationInfoFlags.of(0))
        } else {
            pm.getApplicationInfo(callingPackage!!, 0)
        }

        val validCaller = callerInfo.packageName == "app.grapheneos.gmscompat"
                && (callerInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        if (!validCaller) {
            throw SecurityException()
        }

        // ContentProvider#onCreate() happens before Application#onCreate(), which means that the app
        // might not be initialized yet.
        // Use the main thread executor to ensure that init is completed when
        // seamlesslyUpdateApps() is reached.
        context.mainExecutor.execute {
            App.context.seamlesslyUpdateApps("app.grapheneos.gmscompat.config") {
                // ignore result
            }
        }
    }

    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
