package org.grapheneos.apps.client.utils

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class PackageManagerHelper(private val context: Context) {

    fun install(apkFile: List<File>): Int {
        return installApks(apkFile.toPaths())
    }

    private fun List<File>.toPaths(): Array<String> {
        val result = mutableListOf<String>()
        forEach {
            result.add(it.absolutePath)
        }
        return result.toTypedArray()
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun installApks(files: Array<String>): Int {
        val packageInstaller: PackageInstaller = context.packageManager.packageInstaller
        val nameSizeMap = HashMap<String, Long>()
        val filenameToPathMap = HashMap<String, String>()
        var totalSize: Long = 0
        for (file in files) {
            val listOfFile = File(file)
            if (listOfFile.isFile) {
                nameSizeMap[listOfFile.name] = listOfFile.length()
                filenameToPathMap[listOfFile.name] = file
                totalSize += listOfFile.length()
            }
        }
        val sessionParams = SessionParams(SessionParams.MODE_FULL_INSTALL)
        sessionParams.setSize(totalSize)
        if (JobPsfsMgr(context).autoInstallEnabled()) {
            sessionParams.setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        try {
            val sessionId = packageInstaller.createSession(sessionParams)
            val session = packageInstaller.openSession(sessionId)
            for ((splitName, sizeBytes) in nameSizeMap) {
                val inPath = filenameToPathMap[splitName]
                var inputStream: InputStream? = null
                var out: OutputStream? = null
                try {
                    if (inPath != null) {
                        inputStream = FileInputStream(inPath)
                    }
                    out = session.openWrite(splitName, 0, sizeBytes)
                    val buffer = ByteArray(65536)
                    var c: Int
                    if (inputStream != null) {
                        while (inputStream.read(buffer).also { c = it } != -1) {
                            out.write(buffer, 0, c)
                        }
                    }
                    session.fsync(out)
                } catch (ignored: IOException) {
                    throw ignored
                } finally {
                    try {
                        out?.close()
                        inputStream?.close()
                        session.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
            val callbackIntent = Intent(context.applicationContext, APKInstallReceiver::class.java)
            val pendingIntent =
                PendingIntent.getBroadcast(
                    context.applicationContext, 0, callbackIntent,
                    PendingIntent.FLAG_MUTABLE
                )
            session.commit(pendingIntent.intentSender)
            session.close()
            return sessionId
        } catch (e: IOException) {
            e.printStackTrace()
            return -999
        }
    }

    fun uninstall(packageName: String) {
        val pendingIntent =
            PendingIntent.getBroadcast(
                context.applicationContext,
                0,
                Intent(context.applicationContext, APKInstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE
            )

        val packageInstaller: PackageInstaller = context.packageManager.packageInstaller
        packageInstaller.uninstall(packageName, pendingIntent.intentSender)

    }

    companion object {
        fun Context.pmHelper() = PackageManagerHelper(this)
    }
}