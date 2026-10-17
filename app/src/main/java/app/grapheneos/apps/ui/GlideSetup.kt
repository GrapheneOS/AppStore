package app.grapheneos.apps.ui

import android.content.Context
import app.grapheneos.apps.core.sha256FromString
import app.grapheneos.apps.util.copyTo2
import app.grapheneos.apps.util.openConnection
import app.grapheneos.apps.util.throwResponseCodeException
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.signature.ObjectKey
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection.HTTP_OK
import java.nio.ByteBuffer
import java.security.DigestOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest

@GlideModule
class AppGlide : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.append(VerifiedUrl::class.java, ByteBuffer::class.java, VerifiedUrlLoader.Factory())
    }
}

data class VerifiedUrl(val url: String, val size: Int, val sha256: String)

class VerifiedUrlFetcher(private val verifiedUrl: VerifiedUrl) : DataFetcher<ByteBuffer> {

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in ByteBuffer>) {
        val result: ByteBuffer
        try {
            openConnection(null, verifiedUrl.url, {}).use { conn ->
                if (conn.v.responseCode != HTTP_OK) {
                    throwResponseCodeException(conn.v)
                }

                val arrayOutputStream = ByteArrayOutputStream(verifiedUrl.size)

                val sha256 = MessageDigest.getInstance("SHA-256")

                conn.v.inputStream.use { inputStream ->
                    DigestOutputStream(arrayOutputStream, sha256).use { outputStream ->
                        inputStream.copyTo2(verifiedUrl.size.toLong(), outputStream, null, verifiedUrl.url)
                    }
                }

                if (!sha256.digest().contentEquals(sha256FromString(verifiedUrl.sha256))) {
                    throw GeneralSecurityException("SHA-256 mismatch for ${verifiedUrl.url}")
                }
                result = ByteBuffer.wrap(arrayOutputStream.toByteArray())
            }
        } catch (e: Exception) {
            callback.onLoadFailed(e)
            return
        }
        callback.onDataReady(result)
    }

    override fun cleanup() = Unit
    override fun cancel() = Unit
    override fun getDataClass() = ByteBuffer::class.java
    override fun getDataSource() = DataSource.REMOTE
}

class VerifiedUrlLoader : ModelLoader<VerifiedUrl, ByteBuffer> {
    override fun buildLoadData(model: VerifiedUrl, width: Int, height: Int, options: Options) =
        ModelLoader.LoadData(ObjectKey(model), VerifiedUrlFetcher(model))

    override fun handles(model: VerifiedUrl) = true

    class Factory : ModelLoaderFactory<VerifiedUrl, ByteBuffer> {
        override fun build(multiFactory: MultiModelLoaderFactory) = VerifiedUrlLoader()
        override fun teardown() {}
    }
}
