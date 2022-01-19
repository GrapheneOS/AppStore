package org.grapheneos.apps.client.di

import androidx.annotation.Nullable
import org.grapheneos.apps.client.item.Progress
import org.grapheneos.apps.client.item.network.Response
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
        connection.readTimeout = timeout ?: 30_000
        connection.connectTimeout = timeout ?: 30_000
        connection.addRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.32 Safari/537.36"
        )
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
        val data = connection.inputStream
        val size = (connection.getHeaderField("Content-Length")?.toLong() ?: 0) + file.length()

        val bufferSize = maxOf(DEFAULT_BUFFER_SIZE, data.available())
        val out = FileOutputStream(file, file.exists())

        var bytesCopied: Long = 0
        val buffer = ByteArray(bufferSize)
        var bytes = data.read(buffer)
        while (bytes >= 0) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            bytes = data.read(buffer)

            progressListener.invoke(
                Progress(
                    file.length(), size,
                    (file.length() * 100.0) / size,
                    false
                )
            )
        }
        out.close()
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
