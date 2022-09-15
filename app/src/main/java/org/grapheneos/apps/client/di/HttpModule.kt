package org.grapheneos.apps.client.di

import kotlinx.coroutines.delay
import org.grapheneos.apps.client.item.Progress
import org.grapheneos.apps.client.item.network.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class HttpModule @Inject constructor
    (
    @Named("file") private val file: File,
    @Named("uri") private val uri: String,
    @Named("timeout") private val timeout: Int?,
    @Named("eTag") private val eTag: String?,
    @Named("progressListener") private val progressListener:
        (progress: Progress) -> Unit?
) {
    private var connection: HttpURLConnection = URL(uri).openConnection() as HttpURLConnection

    init {
        addETag()
        addBasicHeader()
    }

    private fun addBasicHeader() {
        connection.apply {
            readTimeout = timeout ?: 30_000
            connectTimeout = timeout ?: 30_000
            addRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.32 Safari/537.36"
            )
            addRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun addETag() {
        eTag?.let { tag ->
            connection.addRequestProperty(
                "If-None-Match",
                tag
            )
        }
    }

    private fun addRange(existingFile: File) {
        if (existingFile.exists()) {
            val range: String = String.format(
                Locale.ENGLISH,
                "bytes=%d-",
                if (existingFile.exists()) existingFile.length() else 0
            )
            connection.addRequestProperty("Range", range)
        }

    }

    fun connect(): Response {
        connection.connect()
        val response = Response(connection.getHeaderField("ETag"), connection.responseCode)
        connection.disconnect()
        return response
    }

    fun saveToFile(clean: Boolean = false) {
        saveAsFile(clean)
    }

    suspend fun saveToFileHandleConnectionsDrop(clean: Boolean = false) {
        var callSuccess = false
        var retryCount = 0
        val maxRetryCount = 20
        val delayAfterFailure = 500L

        while (!callSuccess && retryCount <= maxRetryCount) {
            retryCount++
            try {
                saveAsFile(clean && retryCount == 0)
                callSuccess = true
            } catch (e: IOException) {
                delay(delayAfterFailure)
                if (retryCount >= maxRetryCount) throw e
            }
        }
    }

    private fun isDownloadAsGzipSupported(): Boolean {
        connection.disconnect()
        connection = URL(uri.toGzipUri()).openConnection() as HttpURLConnection
        addBasicHeader()
        connection.connect()
        return connection.responseCode == 200
    }

    private fun uncompressedSize(): Long {
        connection.disconnect()
        connection = URL(uri).openConnection() as HttpURLConnection
        addBasicHeader()
        connection.connect()
        val size = connection.getHeaderField("Content-length")?.toLongOrNull() ?: 0L
        connection.disconnect()
        return size
    }

    private fun String.toGzipUri() = "$this.gz"
    private fun File.asGzipFile() = File(parent, "$name.gz")

    private fun saveAsFile(clean: Boolean = false) {
        connection.disconnect()
        val gzipSupported = isDownloadAsGzipSupported()
        val url = if (gzipSupported) uri.toGzipUri() else uri
        val size = uncompressedSize()
        val outFile = if (gzipSupported) file.asGzipFile() else file

        if (clean) {
            outFile.delete()
            connection = URL(url).openConnection() as HttpURLConnection
            addBasicHeader()
        } else {
            connection = URL(url).openConnection() as HttpURLConnection
            addBasicHeader()
            addRange(outFile)
        }

        connection.connect()
        val stream = connection.inputStream
        val out = FileOutputStream(outFile, outFile.exists())

        stream.use { inputStream ->
            val bufferSize = maxOf(DEFAULT_BUFFER_SIZE, inputStream.available())
            out.use { outputStream ->
                var bytesCopied: Long = 0
                val buffer = ByteArray(bufferSize)
                var bytes = 0
                var first = true

                while (first || (bytes >= 0)) {
                    first = false
                    bytes = inputStream.read(buffer)
                    if (bytes > 0) {
                        outputStream.write(buffer, 0, bytes)
                        bytesCopied += bytes
                    }
                    progressListener.invoke(
                        Progress(
                            outFile.length(), size,
                            (outFile.length() * 100.0) / size,
                            false
                        )
                    )
                }
            }
        }

        if (gzipSupported) {
            GZIPInputStream(FileInputStream(file.asGzipFile())).use { gzipStream ->
                if (file.exists()) {
                    file.delete()
                }
                file.createNewFile()
                val bufferSize = maxOf(DEFAULT_BUFFER_SIZE, gzipStream.available())
                FileOutputStream(file).use { uncompressedStream ->
                    var bytesCopied: Long = 0
                    val buffer = ByteArray(bufferSize)
                    var bytes = 0
                    var first = true

                    while (first || (bytes >= 0)) {
                        first = false
                        bytes = gzipStream.read(buffer)
                        if (bytes > 0) {
                            uncompressedStream.write(buffer, 0, bytes)
                            bytesCopied += bytes
                        }
                    }
                }
            }
        }

        if (file.asGzipFile().exists()) {
            file.asGzipFile().delete()
        }

        progressListener.invoke(
            Progress(
                file.length(), size,
                (file.length() * 100.0) / size,
                false
            )
        )
        connection.disconnect()
    }
}
