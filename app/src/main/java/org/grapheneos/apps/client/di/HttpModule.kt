package org.grapheneos.apps.client.di

import androidx.annotation.Nullable
import kotlinx.coroutines.delay
import org.grapheneos.apps.client.item.Progress
import org.grapheneos.apps.client.item.network.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
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
    @Named("progressListener") @Nullable private val progressListener:
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

    private fun addRange() {
        val range: String = String.format(
            Locale.ENGLISH,
            "bytes=%d-",
            if (file.exists()) file.length() else 0
        )
        connection.addRequestProperty("Range", range)
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

    suspend fun saveToFileHandleConnectionsDrop() {
        var callSuccess = false
        var retryCount = 0
        val maxRetryCount = 20
        val delayAfterFailure = 500L

        while (!callSuccess && retryCount <= maxRetryCount) {
            retryCount++
            try {
                saveAsFile(false)
                callSuccess = true
            } catch (e: SocketTimeoutException) {
                delay(delayAfterFailure)
            } catch (e: SocketException) {
                delay(delayAfterFailure)
            } catch (e: UnknownHostException) {
                delay(delayAfterFailure)
            } catch (e: IOException) {
                delay(delayAfterFailure)
            }
        }
    }

    private fun saveAsFile(clean: Boolean = false) {
        connection.disconnect()

        if (clean) {
            file.delete()
            connection = URL(uri).openConnection() as HttpURLConnection
            addBasicHeader()
        } else {
            connection = URL(uri).openConnection() as HttpURLConnection
            addBasicHeader()
            addRange()
        }

        connection.connect()
        val contentSize = connection.getHeaderField("Content-length")?.toLongOrNull() ?: 0L
        val fileSize = if (file.exists()) file.length() else 0
        val size = contentSize + fileSize

        val data = connection.inputStream
        val out = FileOutputStream(file, file.exists())

        data.use { inputStream ->
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
                            file.length(), size,
                            (file.length() * 100.0) / size,
                            false
                        )
                    )
                }
            }
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
