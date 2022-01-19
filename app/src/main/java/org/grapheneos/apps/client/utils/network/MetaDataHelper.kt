package org.grapheneos.apps.client.utils.network

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.bouncycastle.util.encoders.DecoderException
import org.grapheneos.apps.client.di.DaggerHttpHelperComponent
import org.grapheneos.apps.client.di.HttpHelperComponent.Companion.defaultConfigBuild
import org.grapheneos.apps.client.item.MetaData
import org.grapheneos.apps.client.item.Package
import org.grapheneos.apps.client.item.PackageVariant
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLHandshakeException

class MetaDataHelper constructor(context: Context) {

    private val version = CURRENT_VERSION

    private val metadataFileName = "metadata.${version}.json"
    private val metadataSignFileName = "metadata.${version}.json.${version}.sig"

    private val baseDir = "${context.dataDir.absolutePath}/internet/files/cache/version${version}/"
    private val metadata = File(baseDir, metadataFileName)
    private val sign = File(baseDir, "metadata.json.${version}.sig")

    private val eTagPreferences: SharedPreferences = context.getSharedPreferences(
        "metadata",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val TIMESTAMP_KEY = "timestamp"
    }

    @Throws(
        GeneralSecurityException::class,
        DecoderException::class,
        JSONException::class,
        UnknownHostException::class,
        FileNotFoundException::class,
        SSLHandshakeException::class
    )
    fun downloadNdVerifyMetadata(callback: (metadata: MetaData) -> Unit): MetaData {

        if (!File(baseDir).exists()) File(baseDir).mkdirs()
        try {
            /*download/validate metadata json, sign and pub files*/
            val metadataETag = fetchContent(metadataFileName, metadata)
            val metadataSignETag = fetchContent(metadataSignFileName, sign)

            /*save or updated timestamp this will take care of downgrade*/
            verifyTimestamp()

            /*save/update newer eTag if there is any*/
            saveETag(metadataFileName, metadataETag)
            saveETag(metadataSignFileName, metadataSignETag)

        } catch (e: UnknownHostException) {
            /*
            There is no internet if we still want to continue with cache data
            don't throw this exception and maybe add a flag in
            response that indicate it's cache data
             */
            throw e
        } catch (e: SecurityException) {
            //user can deny INTERNET permission instead of crashing app let user know it's failed
            throw GeneralSecurityException(e.localizedMessage)
        }

        if (!metadata.exists()) {
            throw GeneralSecurityException("file does not exist")
        }
        val message = FileInputStream(metadata).readBytes()

        val signature = FileInputStream(sign)
            .readBytes()
            .decodeToString()
            .substringAfterLast(".pub")
            .replace("\n", "")
            .toByteArray()

        val verified = FileVerifier(PUBLIC_KEY)
            .verifySignature(
                message,
                signature.decodeToString()
            )

        /*This does not return anything if timestamp verification fails it throw GeneralSecurityException*/
        verifyTimestamp()

        if (verified) {
            val jsonData = JSONObject(message.decodeToString())
            val response = MetaData(
                jsonData.getLong("time"),
                jsonData.getJSONObject("apps").toPackages()
            )
            callback.invoke(response)
            return response
        }
        /*verification has been failed. Deleting config related to this version*/
        deleteFiles()
        throw GeneralSecurityException("verification failed")
    }

    private fun JSONObject.toPackages(): Map<String, Package> {
        val result = mutableMapOf<String, Package>()

        keys().forEach { pkgName ->
            val variantsJson = getJSONObject(pkgName)
            val channelKeys = variantsJson.keys()

            val variants = mutableMapOf<String, PackageVariant>()

            channelKeys.forEach { channelName ->
                val channelItemsJson = variantsJson.getJSONArray(channelName)
                val sdkFound = 0
                val maxSupportedSdk = Build.VERSION.SDK_INT

                for (itemIndex in 0 until channelItemsJson.length()) {
                    val channelItemJson = channelItemsJson.getJSONObject(itemIndex)

                    val packages = channelItemJson.getJSONArray("packages")
                    val hashes = channelItemJson.getJSONArray("hashes")
                    val appName = channelItemJson.getString("label")
                    val minSdkVersion = channelItemJson.getInt("minSdkVersion")
                    val versionCode = channelItemJson.getInt("versionCode")
                    val dependenciesArray = channelItemJson.getJSONArray("dependencies")
                    val dependencies = mutableListOf<String>()

                    for (dependenciesIndex in 0 until dependenciesArray.length()) {
                        dependencies.add(dependenciesArray.getString(dependenciesIndex))
                    }

                    if (packages.length() != hashes.length()) {
                        throw GeneralSecurityException("Package hash size miss match")
                    }
                    val packageInfoMap = mutableMapOf<String, String>()
                    for (sizeIndex in 0 until hashes.length()) {
                        packageInfoMap[packages.getString(sizeIndex)] = hashes.getString(sizeIndex)
                    }

                    if (minSdkVersion in sdkFound..maxSupportedSdk) {
                        variants[channelName] = PackageVariant(
                            appName,
                            pkgName,
                            channelName,
                            packageInfoMap,
                            versionCode,
                            dependencies
                        )
                    }
                }
            }
            if (variants.isNotEmpty()) {
                result[pkgName] = Package(pkgName, variants)
            }
        }
        return result
    }

    @Throws(UnknownHostException::class, GeneralSecurityException::class, SecurityException::class)
    private fun fetchContent(pathAfterBaseUrl: String, file: File): String {

        val url = "${BASE_URL}/${pathAfterBaseUrl}"

        val caller = DaggerHttpHelperComponent.builder()
            .defaultConfigBuild()
            .uri(url)
            .file(file)
            .apply {
                val eTAG = getETag(pathAfterBaseUrl)
                if (file.exists() && eTAG != null) {
                    addETag(eTAG)
                }
            }
            .build()
            .downloader()

        val response = caller.connect()

        when (response.resCode) {
            304 -> {
                return getETag(pathAfterBaseUrl)!!
            }
            in 200..299 -> {
                caller.saveToFile(clean = true)
            }
            else -> {
                throw GeneralSecurityException("Server responded with unexpected response code ")
            }
        }

        return response.eTag ?: ""
    }

    private fun deleteFiles() = File(baseDir).deleteRecursively()

    private fun String.toTimestamp(): Long? {
        return try {
            JSONObject(this).getLong("time")
        } catch (e: JSONException) {
            null
        }
    }

    private fun saveETag(key: String, s: String?) {
        eTagPreferences.edit().putString(key, s).apply()
    }

    private fun getETag(key: String): String? {
        return eTagPreferences.getString(key, null)
    }

    @Throws(GeneralSecurityException::class)
    private fun verifyTimestamp() {
        val timestamp = FileInputStream(metadata).readBytes().decodeToString().toTimestamp()
        val lastTimestamp = eTagPreferences.getLong(TIMESTAMP_KEY, 0L)

        if (timestamp == null) throw GeneralSecurityException("current file timestamp not found!")

        if (lastTimestamp != 0L && lastTimestamp > timestamp || TIMESTAMP > timestamp) {
            deleteFiles()
            throw GeneralSecurityException("downgrade is not allowed!")
        }
        eTagPreferences.edit().putLong(TIMESTAMP_KEY, timestamp).apply()
    }
}
