package org.grapheneos.apps.client.utils.network

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.bouncycastle.util.encoders.DecoderException
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
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
import java.io.InputStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLHandshakeException

class MetaDataHelper constructor(context: Context) {

    private val version = CURRENT_VERSION

    private val metadataFileName = "metadata.${version}.json"
    private val metadataSignFileName = "metadata.${version}.json.${version}.sig"

    private val baseDir = context.metadataVerifiedDir()
    private val tmpDir = context.metadataTmpDir()

    private val tmpMetaData = File(tmpDir, metadataFileName)
    private val tmpSign = File(tmpDir, "metadata.json.${version}.sig")

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
        SSLHandshakeException::class,
        ConnectException::class
    )
    fun downloadAndVerifyMetadata(callback: (metadata: MetaData) -> Unit): MetaData {

        //create both tmp and base dir if it doesn't exist
        if (!File(baseDir).exists()) File(baseDir).mkdirs()
        File(tmpDir).clean()

        try {
            /*download metadata json, sign and pub files in tmp dir*/
            val metadataETag = fetchContent(metadataFileName, tmpMetaData, metadata)
            val metadataSignETag = fetchContent(metadataSignFileName, tmpSign, sign)

            //if file doesn't exist in local tmp dir means existing file are up to date aka http response code 304
            if (tmpMetaData.exists() && tmpSign.exists()) {
                verifyMetadata(tmp = true)

                //tmp dir content is verified now, delete the existing content of base dir
                // and move the verified stuff from tmp dir
                File(baseDir).clean()
                tmpMetaData.renameTo(metadata)
                tmpSign.renameTo(sign)
                File(tmpDir).clean()

                //verify the base dir content again
                val timestamp = verifyMetadata(tmp = false).toTimestamp()

                //base dir content is verified now, save eTags and timestamp
                saveETag(metadataFileName, metadataETag)
                saveETag(metadataSignFileName, metadataSignETag)
                eTagPreferences.edit().putLong(TIMESTAMP_KEY, timestamp).apply()
            }

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
            throw GeneralSecurityException(App.getString(R.string.fileDoesNotExist))
        }
        val message = verifyMetadata(tmp = false)
        val jsonData = JSONObject(message)
        val response = MetaData(
            jsonData.getLong("time"),
            jsonData.getJSONObject("apps").toPackages()
        )
        callback.invoke(response)
        return response
    }

    private fun File.clean() {
        if (exists()) {
            list()?.forEach {
                File(it).deleteRecursively()
            }
        } else {
            mkdirs()
        }
    }

    private fun verifyMetadata(tmp: Boolean): String {
        val metadataFile = if (tmp) tmpMetaData else metadata
        val signFile = if (tmp) tmpSign else sign

        if (!metadataFile.exists() || !signFile.exists()) {
            throw GeneralSecurityException(App.getString(R.string.fileDoesNotExist))
        }

        val message = FileInputStream(metadataFile).readBytes()
        val signature = FileInputStream(signFile).toSignByteArray()
        val messageAsString = message.decodeToString()

        val lastTimestamp = eTagPreferences.getLong(TIMESTAMP_KEY, 0L)
        val timestamp = messageAsString.toTimestamp()

        return try {
            FileVerifier(PUBLIC_KEY)
                .verifySignature(
                    message,
                    signature.decodeToString()
                )

            if ((lastTimestamp != 0L && lastTimestamp > timestamp) || TIMESTAMP > timestamp) {
                throw GeneralSecurityException(App.getString(R.string.downgradeNotAllowed))
            }
            messageAsString
        } catch (e: GeneralSecurityException) {
            if (tmp) deleteTmpFiles() else deleteSavedFiles()
            throw e
        }
    }

    private fun InputStream.toSignByteArray(): ByteArray {
        return readBytes()
            .decodeToString()
            .substringAfterLast(".pub")
            .replace("\n", "")
            .toByteArray()
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
                        throw GeneralSecurityException(App.getString(R.string.hashSizeMismatch))
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

    @Throws(
        UnknownHostException::class,
        GeneralSecurityException::class,
        SecurityException::class,
        ConnectException::class
    )
    private fun fetchContent(
        pathAfterBaseUrl: String,
        downloadTo: File,
        existingFile: File
    ): String {

        val url = "${BASE_URL}/${pathAfterBaseUrl}"

        val caller = DaggerHttpHelperComponent.builder()
            .defaultConfigBuild()
            .uri(url)
            .file(downloadTo)
            .apply {
                val eTAG = getETag(pathAfterBaseUrl)
                if (existingFile.exists() && eTAG != null) {
                    addETag(eTAG)
                }
            }
            .build()
            .downloader()

        val response = caller.connect()

        when (response.resCode) {
            304 -> {
                return response.eTag ?: ""
            }
            in 200..299 -> {
                caller.saveToFile(clean = true)
            }
            else -> {
                throw GeneralSecurityException(App.getString(R.string.serverUnexpectedResponse))
            }
        }

        return response.eTag ?: ""
    }

    private fun deleteTmpFiles() = File(tmpDir).deleteRecursively()
    private fun deleteSavedFiles() = File(baseDir).deleteRecursively()

    private fun String.toTimestamp(): Long {
        return try {
            JSONObject(this).getLong("time")
        } catch (e: JSONException) {
            throw GeneralSecurityException(App.getString(R.string.fileTimestampMissing))
        }
    }

    private fun saveETag(key: String, s: String?) {
        eTagPreferences.edit().putString(key, s).apply()
    }

    private fun getETag(key: String): String? {
        return eTagPreferences.getString(key, null)
    }
}
