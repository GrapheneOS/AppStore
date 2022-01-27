package org.grapheneos.apps.client.di

import androidx.annotation.Nullable
import org.grapheneos.apps.client.item.Progress
import org.grapheneos.apps.client.item.network.Response
import org.grapheneos.apps.client.utils.network.Encoding
import java.io.File
import java.io.FileOutputStream
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
    @Named("progressListener") @Nullable private val progressListener:
        (progress: Progress) -> Unit?
) {
    private var connection: HttpURLConnection = URL(uri).openConnection() as HttpURLConnection

    init {
        addETag()
        addBasicHeader()
    }

    private fun addBasicHeader(
        supportedEncodings: List<Encoding> = listOf(
            Encoding.Gzip(),
            Encoding.Uncompressed()
        )
    ) {
        connection.apply {
            readTimeout = timeout ?: 30_000
            connectTimeout = timeout ?: 30_000
            addRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.32 Safari/537.36"
            )

            val encodings = StringBuilder()
            for (i in supportedEncodings) {
                encodings.append(i.code)
                if (supportedEncodings.last() != i && supportedEncodings.first() == i) {
                    encodings.append(", ")
                }
            }
            addRequestProperty("Accept-Encoding", encodings.toString())
        }
    }

    private fun getRawFileSize(): Long {
        connection = URL(uri).openConnection() as HttpURLConnection
        addBasicHeader(listOf(Encoding.Uncompressed()))
        connection.apply {
            addRequestProperty("Accept", "*/*")
            addRequestProperty("Accept-Encoding", "identity")
            connect()
        }
        val size = connection.getHeaderField("Content-length")
        connection.disconnect()
        return size?.toLong() ?: 0
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

        connection.disconnect()
        val size = getRawFileSize()

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
        val data = when (connection.contentEncoding) {
            Encoding.Gzip().code -> {
                //gzip compression does not support range header so delete any
                // existing file because download is gonna start from zero
                file.delete()
                GZIPInputStream(connection.inputStream)
            }
            else ->
                connection.inputStream
        }

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
