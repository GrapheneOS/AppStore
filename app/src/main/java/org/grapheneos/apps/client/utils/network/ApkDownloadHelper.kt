package org.grapheneos.apps.client.utils.network

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.di.DaggerHttpHelperComponent
import org.grapheneos.apps.client.di.HttpHelperComponent.Companion.defaultConfigBuild
import org.grapheneos.apps.client.item.DownloadCallBack
import org.grapheneos.apps.client.item.PackageVariant
import org.grapheneos.apps.client.item.Progress
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLHandshakeException

class ApkDownloadHelper constructor(private val context: Context) {

    @RequiresPermission(Manifest.permission.INTERNET)
    suspend fun downloadAndVerifySHA256(
        variant: PackageVariant,
        progressListener: (read: Long, total: Long, doneInPercent: Double, taskCompleted: Boolean) -> Unit,
    ): DownloadCallBack {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        return try {

            val vCode = variant.versionCode
            val pkgName = variant.pkgName

            val resultDir = variant.getResultDir(context)
            val downloadDir = variant.getDownloadDir(context)

            downloadDir.mkdirs()
            resultDir.mkdirs()

            val completeProgress = mutableMapOf<String, Progress>()
            val size = variant.packagesInfo.size
            val downloadTasks = variant.packagesInfo.map { (fileName, sha256Hash) ->
                scope.async {
                    val downloadableFile = File(downloadDir.absolutePath, fileName)
                    val resultFile = File(resultDir.absolutePath, fileName)
                    val uri = "${PACKAGE_DIR_URL}${pkgName}/${vCode}/${fileName}"

                    ensureActive()
                    if (resultFile.exists() && verifyHash(resultFile, sha256Hash)) {
                        val progress = Progress(
                            resultFile.length(),
                            resultFile.length(),
                            100.0,
                            size == 1
                        )
                        if (size != 1) {
                            completeProgress[fileName] = progress
                        } else {
                            progressListener.invoke(
                                progress.read,
                                progress.total,
                                progress.doneInPercent,
                                progress.taskCompleted
                            )
                        }
                        return@async resultFile
                    } else {

                        DaggerHttpHelperComponent.builder()
                            .defaultConfigBuild()
                            .file(downloadableFile)
                            .uri(uri)
                            .addProgressListener { newProgress ->
                                var read = 0L
                                var total = 0L
                                var completed = true
                                var calculationSize = 0

                                ensureActive()
                                completeProgress[fileName] = newProgress
                                completeProgress.forEach { (_, progress) ->
                                    read += progress.read
                                    total += progress.total
                                    completed = completed && progress.taskCompleted
                                    calculationSize++
                                }

                                val shouldCompute =
                                    calculationSize == size && total.toInt() != 0
                                val doneInPercent =
                                    if (shouldCompute) (read * 100.0) / total else -1.0

                                progressListener.invoke(
                                    read,
                                    total,
                                    doneInPercent,
                                    completed
                                )

                            }
                            .build()
                            .downloader()
                            .saveToFile()

                        ensureActive()
                        if (!verifyHash(downloadableFile, sha256Hash)) {
                            downloadableFile.delete()
                            throw GeneralSecurityException(App.getString(R.string.hashMismatch))
                        }
                        downloadableFile.renameTo(resultFile)
                        return@async resultFile
                    }
                }
            }
            val apks = downloadTasks.awaitAll()
            DownloadCallBack.Success(apks = apks)
        } catch (e: IOException) {
            DownloadCallBack.IoError(e)
        } catch (e: GeneralSecurityException) {
            DownloadCallBack.SecurityError(e)
        } catch (e: UnknownHostException) {
            DownloadCallBack.UnknownHostError(e)
        } catch (e: SSLHandshakeException) {
            DownloadCallBack.SecurityError(e)
        } catch (e: java.net.SocketException) {
            DownloadCallBack.IoError(e)
        } catch (e: CancellationException) {
            DownloadCallBack.Canceled()
        } finally {
            scope.cancel()
        }
    }

    @Throws(NoSuchAlgorithmException::class, GeneralSecurityException::class)
    private fun verifyHash(downloadedFile: File, sha256Hash: String): Boolean {
        try {
            val downloadedFileHash = bytesToHex(
                MessageDigest.getInstance("SHA-256").digest(downloadedFile.readBytes())
            )
            if (sha256Hash == downloadedFileHash) return true
        } catch (e: NoSuchAlgorithmException) {
            throw GeneralSecurityException(App.getString(R.string.sha256Unsupported))
        }
        return false
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

}
