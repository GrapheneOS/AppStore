package org.grapheneos.apps.client.di

import androidx.annotation.Nullable
import org.grapheneos.apps.client.item.Progress
import org.grapheneos.apps.client.item.network.Response
import java.io.File
import java.io.FileInputStream
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

    private fun getRawFileSize(newUri: String = uri): Long {
        connection = URL(newUri).openConnection() as HttpURLConnection
        addBasicHeader()
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

    private fun addRange(cFile: File = file) {
        val range: String = String.format(
            Locale.ENGLISH,
            "bytes=%d-",
            if (cFile.exists()) cFile.length() else 0
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
        val isCompressed = file.extension in listOf("apk", "json")
        val newPath = if (isCompressed) "${uri}.gz" else uri
        val compSize = getRawFileSize(newPath)
        val compFile = if (isCompressed) File(file.getParent()!!, "${file.getName()}.gz") else file

        if (clean) {
            file.delete()
            connection = URL(newPath).openConnection() as HttpURLConnection
            addBasicHeader()
        } else {
            connection = URL(newPath).openConnection() as HttpURLConnection
            addBasicHeader()
            addRange(compFile)
        }

        connection.connect()
        val data =  connection.inputStream

        val bufferSize = maxOf(DEFAULT_BUFFER_SIZE, data.available())
        val out = FileOutputStream(compFile, compFile.exists())

        var bytesCopied: Long = 0
        val buffer = ByteArray(bufferSize)
        var bytes = data.read(buffer)
        while (bytes >= 0) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            bytes = data.read(buffer)

            progressListener.invoke(
                Progress(
                    compFile.length(), compSize,
                    (compFile.length() * 100.0) / compSize,
                    false
                )
            )
        }
        out.close()
        progressListener.invoke(
            Progress(
                compFile.length(), compSize,
                (compFile.length() * 100.0) / compSize,
                false
            )
        )
        connection.disconnect()
        if (!isCompressed) return

        /* Extraction section for downloaded compressed files */
        GZIPInputStream(compFile.inputStream()).use { input ->
            val output = FileOutputStream(file, file.exists())
            val uBufferSize = maxOf(DEFAULT_BUFFER_SIZE, input.available())

            var uBytesCopied: Long = 0
            val uBuffer = ByteArray(uBufferSize)
            var uBytes = input.read(uBuffer)
            while (uBytes >= 0) {
                output.write(uBuffer, 0, uBytes)
                uBytesCopied += uBytes
                uBytes = input.read(uBuffer)

                progressListener.invoke(
                    Progress(
                        file.length(), size,
                        (file.length() * 100.0) / size,
                        false
                    )
                )
            }
            output.close()
        }
        progressListener.invoke(
            Progress(
                file.length(), size,
                (file.length() * 100.0) / size,
                false
            )
        )
        compFile.delete()
    }
}
