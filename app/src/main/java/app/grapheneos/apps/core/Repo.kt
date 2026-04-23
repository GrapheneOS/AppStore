package app.grapheneos.apps.core

import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.security.FileIntegrityManager
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.util.SparseArray
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.core.util.getOrElse
import androidx.core.util.keyIterator
import androidx.core.util.size
import app.grapheneos.apps.BuildConfig
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.util.asStringList
import app.grapheneos.apps.util.checkMainThread
import app.grapheneos.apps.util.getPackageInfoOrNull
import app.grapheneos.apps.util.isEven
import app.grapheneos.apps.util.maybeGetSystemFeatureInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

const val REPO_BASE_URL = BuildConfig.REPO_BASE_URL

class Repo(json: JSONObject, val eTag: String, val isDummy: Boolean = false) {
    val timestamp = json.getLong("time")

    val groups = mutableMapOf<String, RPackageGroup>()
    private val renamedPackages = ArrayMap<String, String>()

    fun translateManifestPackageName(name: String) = renamedPackages[name] ?: name

    val packages: HashMap<String, RPackageContainer> = run {
        val packagesJson = json.getJSONObject("packages")
        val map = HashMap<String, RPackageContainer>(packagesJson.length())

        // Collect packages renamed via original-package system. Applicable only to preinstalled apps.
        for (manifestPackageName in packagesJson.keys()) {
            val originalPackage = packagesJson.getJSONObject(manifestPackageName)
                .opt("originalPackage") as String?

            if (originalPackage != null) {
                pkgManager.getPackageInfoOrNull(originalPackage)?.let {
                    val ai = it.applicationInfo
                    if (ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                        renamedPackages.put(manifestPackageName, originalPackage)
                    }
                }
            } else {
                // PackageInfos are cached by the OS. Populating this cache in advance speeds up
                // subsequent requests
                CoroutineScope(Dispatchers.Default).launch {
                    pkgManager.getPackageInfoOrNull(manifestPackageName)
                }
            }
        }

        for (manifestPackageName in packagesJson.keys()) {
            val packageContainerJson = packagesJson.getJSONObject(manifestPackageName)

            if (!checkStaticDeps(packageContainerJson, manifestPackageName, this)) {
                continue
            }

            val packageName = translateManifestPackageName(manifestPackageName)

            if (Build.VERSION.SDK_INT == 34 && isPrivilegedInstaller
                && packageName != manifestPackageName && shouldSkipRenamedPackages()
            ) {
                continue
            }

            val res = RPackageContainer(this@Repo, packageName, manifestPackageName, packageContainerJson)
            if (res.variants.isNotEmpty()) {
                map.put(packageName, res)
            }
        }

        map
    }

    val fsVerityCertificateId: Int? = run {
        if (Build.VERSION.SDK_INT >= 35) {
            // fs-verity certificates are not used by the OS since SDK 35
            return@run null
        }

        if (!isPrivilegedInstaller) {
            if (!pkgManager.canRequestPackageInstalls()) {
                // isAppSourceCertificateTrusted() below requires {REQUEST_,}INSTALL_PACKAGES
                return@run null
            }
        }

        val certs = json.optJSONObject("fsVerityCerts") ?: return@run null
        val fim: FileIntegrityManager = appContext.getSystemService() ?: return@run null

        val certFactory = CertificateFactory.getInstance("X.509")

        for (id in certs.keys()) {
            val certBase64 = certs.getString(id)
            val certBytes = Base64.decode(certBase64, Base64.DEFAULT)

            val cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

            @Suppress("DEPRECATION") // not deprecated for SDK < 35
            if (fim.isAppSourceCertificateTrusted(cert)) {
                return@run id.toInt()
            }
        }
        return@run null
    }
}

// ReleaseChannel enum entries are expected to be ordered from least stable to most stable by the
// package variant selection code.
enum class ReleaseChannel(@param:StringRes val uiName: Int) {
    alpha(R.string.release_channel_alpha),
    beta(R.string.release_channel_beta),
    stable(R.string.release_channel_stable),
}

fun findRPackage(variants: List<RPackage>, channel: ReleaseChannel): RPackage {
    // variants are sorted by stability in ascending order (from least stable to most stable)
    return variants.find { it.releaseChannel >= channel } ?: variants.last()
}

enum class PackageSource(@param:StringRes val uiName: Int) {
    GrapheneOS(R.string.pkg_source_grapheneos),
    GrapheneOS_build(R.string.pkg_source_grapheneos_build),
    Mirror(R.string.pkg_source_mirror),
    Google(R.string.pkg_source_google),
}

class RPackageGroup(val name: String) {
    val packages = mutableListOf<RPackageContainer>()
    var releaseChannelOverride: ReleaseChannel? = null
}

// Contains properties that are common to variants of the package, and the list of variants.
// Some of the common properties can be overridden in its variants
class RPackageContainer(val repo: Repo, val packageName: String,
                        // different from packageName if renamed via the original-package system
                        val manifestPackageName: String,
                        json: JSONObject)
{
    val description = json.opt("description") as String?
    val source: PackageSource = (json.opt("source") as String?).let {
        if (it != null) {
            PackageSource.valueOf(it)
        } else {
            PackageSource.GrapheneOS_build
        }
    }

    // whether this is a resource-only package. enforced during package installation
    val noCode = json.optBoolean("noCode", false)
    // whether to show this package on the main screen
    val isTopLevel = json.optBoolean("isTopLevel", true)
    val showAutoUpdateNotifications = json.optBoolean("showAutoUpdateNotifications", !noCode)

    val isSharedLibrary = json.optBoolean("isSharedLibrary", false)

    // if this is a noCode package, defines which packages are allowed to trigger its immediate update
    val packagesAllowedToTriggerUpdate: List<String> =
        json.optJSONArray("packagesAllowedToTriggerUpdate")?.asStringList()?.map {
            repo.translateManifestPackageName(it)
        } ?: emptyList()

    // SHA-256 digests of valid signing certificates
    val validCertDigests: Array<ByteArray> =
            json.getJSONArray("signatures" /* historical name */).asStringList().map {
        val arr = hexStringToByteArray(it)
        check(arr.size == (256 / 8))
        arr
    }.toTypedArray()

    val iconUrl: String? = run {
        val iconType = json.opt("iconType") as String?
        if (iconType != null) {
            "$REPO_BASE_URL/packages/$manifestPackageName/icon.$iconType"
        } else {
            null
        }
    }

    // Used for setting release channel for packages that are closely linked together.
    // This allows to significantly simplify the dependency resolution process (otherwise release
    // channel could be changed for each of them independently)
    val group: RPackageGroup? = run {
        val groupName = json.opt("group") as String? ?: return@run null
        val group = repo.groups.getOrPut(groupName) {
            RPackageGroup(groupName)
        }
        group.packages.add(this)
        group
    }

    val dependencies: Array<Dependency>? = parseDependencies(json, repo)

    val variants: List<RPackage> = json.getJSONObject("variants").let { variantJson ->
        val pkgs = arrayOfNulls<RPackage>(ReleaseChannel.entries.size)

        for (versionString in variantJson.keys()) {
            val jo = variantJson.getJSONObject(versionString)

            val minSdk = jo.optInt("minSdk", 0)
            if (minSdk > Build.VERSION.SDK_INT) {
                continue
            }

            val maxSdk = jo.optInt("maxSdk", Int.MAX_VALUE)
            if (maxSdk < Build.VERSION.SDK_INT) {
                continue
            }

            val abis = jo.optJSONArray("abis")?.asStringList()
            if (abis != null) {
                // intentionally don't support secondary ABIs
                val abi = Build.SUPPORTED_ABIS.first()
                if (abis.none { it == abi }) {
                    continue
                }
            }

            if (!checkStaticDeps(jo, manifestPackageName, repo)) {
                continue
            }

            val pkg = RPackage(this, versionString.toLong(), abis?.toTypedArray(), repo, jo)

            val arrayIndex = pkg.releaseChannel.ordinal

            val prevPkg = pkgs[arrayIndex]

            // make sure there's at most one package in each release channel
            if (prevPkg != null) {
                if (prevPkg.versionCode > pkg.versionCode) {
                    continue
                }
                if (prevPkg.abis != null && pkg.abis != null) {
                    val prevNumAbiMismatches =
                        prevPkg.abis.count { !Build.SUPPORTED_ABIS.contains(it) } +
                                Build.SUPPORTED_ABIS.count { !prevPkg.abis.contains(it) }

                    val numAbiMismatches = pkg.abis.count { !Build.SUPPORTED_ABIS.contains(it) } +
                            Build.SUPPORTED_ABIS.count { !pkg.abis.contains(it) }

                    if (prevNumAbiMismatches < numAbiMismatches) {
                        continue
                    }
                }
            }

            pkgs[arrayIndex] = pkg
        }

        return@let pkgs.filterNotNull()
    }

    val hasFsvSigSignatures = if (Build.VERSION.SDK_INT >= 35) {
        // fsv_sig are not used by the OS since SDK 35, v4 APK signatures are used instead
        false
    } else {
        json.optBoolean("hasFsVeritySignatures", false)
    }

    val requestUpdateOwnership = json.optBoolean("requestUpdateOwnership", true)

    // Opt out of bulk updates that are performed by the auto-update job and by the "Update all"
    // button. This option is intended for packages that are able to self-update, such as app stores.
    val optOutOfBulkUpdates = json.optBoolean("optOutOfBulkUpdates", false)

    fun getPackage(channel: ReleaseChannel): RPackage {
        return findRPackage(variants, channel)
    }
}

private val emptyDependencyArray = emptyArray<Dependency>()

private fun parseDependencies(json: JSONObject, repo: Repo): Array<Dependency>? {
    val arr: JSONArray? = json.optJSONArray("deps2") ?: json.optJSONArray("deps")
    return if (arr != null) {
        arr.asStringList().map { Dependency(it, repo) }.toTypedArray()
    } else {
        null
    }
}

// "Repo package"
class RPackage(val common: RPackageContainer, val versionCode: Long, val abis: Array<String>?, repo: Repo, json: JSONObject) {
    val packageName: String = common.packageName
    val manifestPackageName: String
        get() = common.manifestPackageName
    val source: PackageSource
        get() = common.source

    val label = json.getString("label")
    val versionName = json.opt("versionName") as String? ?: versionCode.toString()
    val description = json.opt("description") as String? ?: common.description
    val releaseNotes = json.opt("releaseNotes") as String?

    val dependencies: Array<Dependency> = parseDependencies(json, repo) ?: common.dependencies ?: emptyDependencyArray

    val releaseChannel = ReleaseChannel.valueOf(json.optString("channel", ReleaseChannel.stable.name))

    val apks: List<Apk> = run {
        val names = json.getJSONArray("apks")
        val hashes = json.getJSONArray("apkHashes")
        val sizes = json.getJSONArray("apkSizes")
        val gzSizes = json.getJSONArray("apkGzSizes")

        val len = names.length()
        require(hashes.length() == len)
        require(sizes.length() == len)
        require(gzSizes.length() == len)

        val list = ArrayList<Apk>(len)

        for (i in 0 until len) {
            val name = names.getString(i)
            val sha256 = hexStringToByteArray(hashes.getString(i))
            require(sha256.size == (256 / 8))
            val apk = Apk(this, name, sha256, sizes.getLong(i), gzSizes.getLong(i))
            if (apk.type == Apk.Type.ABI && apk.qualifier != deviceAbi.apkSplitQualifier) {
                continue
            }
            list.add(apk)
        }
        list
    }

    val hasV4Signatures = if (Build.VERSION.SDK_INT >= 35) {
        // v4 signatures are used by the OS to enable fs-verity for APKs
        json.optBoolean("hasV4Signatures", false)
    } else {
        // v4 signatures are supported since SDK 30, but before SDK 35 they were used only for
        // IncFS-backed APK streaming, which isn't used by this installer
        false
    }

    fun collectNeededApks(config: Configuration): List<Apk> {
        checkMainThread()

        val res = mutableListOf<Apk>()

        val availableDensities = SparseArray<MutableList<Apk>>()
        val pkgState = PackageStates.getPackageState(packageName)
        val neededLocales = getNeededLocales(config, pkgState)

        apks.forEach { apk ->
            val qualifier = apk.qualifier

            when (apk.type) {
                Apk.Type.UNCONDITIONAL,
                // unneeded ABI splits are filtered out during repo parsing
                Apk.Type.ABI ->
                    res.add(apk)

                Apk.Type.LANGUAGE -> {
                    if (neededLocales.contains(Locale.Builder().setLanguage(qualifier).build())) {
                        res.add(apk)
                    }
                }
                Apk.Type.DENSITY -> {
                    val dpi = when (qualifier) {
                        "ldpi" -> DisplayMetrics.DENSITY_LOW
                        "mdpi" -> DisplayMetrics.DENSITY_MEDIUM
                        "tvdpi" -> DisplayMetrics.DENSITY_TV
                        "hdpi" -> DisplayMetrics.DENSITY_HIGH
                        "xhdpi" -> DisplayMetrics.DENSITY_XHIGH
                        "xxhdpi" -> DisplayMetrics.DENSITY_XXHIGH
                        "xxxhdpi" -> DisplayMetrics.DENSITY_XXXHIGH
                        else -> 0
                    }
                    val dpiApks = availableDensities.getOrElse(dpi) {
                        val l = ArrayList<Apk>(7)
                        availableDensities.put(dpi, l)
                        l
                    }
                    dpiApks.add(apk)
                }
            }
        }

        if (availableDensities.size != 0) {
            val targetDensity = config.densityDpi

            val sortedKeys = availableDensities.keyIterator().asSequence().sorted().toList()
            val key = sortedKeys.firstOrNull { it >= targetDensity } ?: sortedKeys.last()
            res.addAll(availableDensities[key])
        }

        return res
    }

    private fun getNeededLocales(config: Configuration, pkgState: PackageState): Set<Locale> {
        checkMainThread()
        val TAG = "getNeededLocales"

        var cache = localeCache

        val locales: LocaleList = config.getLocales()
        val tags = locales.toLanguageTags()
        if (cache != null) {
            if (cache.first != tags) {
                Log.d(TAG, "tags changed: were ${cache.first} now $tags")
                cache = null
            }
        }
        if (cache == null) {
            val len = locales.size()
            val set = ArraySet<Locale>(len)
            for (i in 0 until len) {
                val locale = locales.get(i)
                set.add(Locale.Builder().setLanguage(locale.language).build())
            }
            cache = Pair(tags, set)
            localeCache = cache
        }

        val globalLocales = cache.second

        if (Build.VERSION.SDK_INT >= 33) {
            val pkgSpecificLocales = pkgState.pkgSpecificLocales ?: run {
                if (pkgState.osPackageInfo == null) {
                    return@run LocaleList.getEmptyLocaleList()
                }
                val localeManager = localeManager!!
                val list = try {
                    localeManager.getApplicationLocales(packageName)
                } catch (e: Exception) {
                    // getApplicationLocales() is allowed only if we are currently the
                    // installer-of-record for this package. It also could have been racily
                    // uninstalled, which results in an IllegalArgumentException
                    LocaleList.getEmptyLocaleList()
                }
                pkgState.pkgSpecificLocales = list
                list
            }

            if (!pkgSpecificLocales.isEmpty) {
                return globalLocales
            }

            val set = ArraySet(globalLocales)
            for (i in 0 until pkgSpecificLocales.size()) {
                set.add(pkgSpecificLocales.get(i))
            }
            return set
        } else {
            return globalLocales
        }
    }

    companion object {
        private var localeCache: Pair<String, ArraySet<Locale>>? = null
    }
}

class Apk(
    val pkg: RPackage,
    val name: String,
    val sha256: ByteArray,
    val size: Long,
    val compressedSize: Long,
) {
    var qualifier = ""

    val type: Type = run {
        val separator = "config."
        val separatorIdx = name.lastIndexOf(separator)
        if (separatorIdx < 0) {
            Type.UNCONDITIONAL
        } else {
            val qualifier = name.substring(separatorIdx + separator.length, name.length - ".apk".length)
            this.qualifier = qualifier

            if (qualifier.endsWith("dpi")) {
                Type.DENSITY
            } else if (Abi.entries.any { it.apkSplitQualifier == qualifier }) {
                Type.ABI
            } else {
                Type.LANGUAGE
            }
        }
    }

    fun downloadUrl() = "$REPO_BASE_URL/packages/${pkg.manifestPackageName}/${pkg.versionCode}/$name.gz"

    enum class Type {
        UNCONDITIONAL,
        ABI,
        LANGUAGE,
        DENSITY,
    }

    enum class Abi(val osName: String, val apkSplitQualifier: String) {
        ARM_V7("armeabi-v7a", "armeabi_v7a"),
        X86("x86", "x86"),
        ARM_V8("arm64-v8a", "arm64_v8a"),
        X86_64("x86_64", "x86_64")
    }
}

private val deviceAbi: Apk.Abi = run {
    // Intentionally don't support secondary ABIs. They are expected to work worse than the primary ABI.
    val osName: String = Build.SUPPORTED_ABIS.first()
    Apk.Abi.entries.first { it.osName == osName }
}

// Used only for static deps. If it was used for dynamic deps, dependency resolution would become
// too complex, especially during package updates.
private class ComplexDependency(string: String) {
    val lhs: String // left-hand-side
    val op: String
    val version: Long

    init {
        val tokens = string.split(" ")
        lhs = tokens[0]
        if (tokens.size == 1) {
            op = ">="
            version = 0
        } else {
            check(tokens.size == 3)
            op = tokens[1]
            version = tokens[2].toLong()
        }
    }

    fun check(presentVersion: Long): Boolean {
        return when (op) {
            ">=" -> presentVersion >= version
            "==" -> presentVersion == version
            "<" -> presentVersion < version
            else -> throw IllegalStateException(op)
        }
    }
}

private fun checkStaticDeps(json: JSONObject, dependentManifestPkgName: String, repo: Repo): Boolean {
    json.optJSONArray("supportedDevices")?.asStringList()?.let { devices ->
        if (!devices.contains(Build.DEVICE)) {
            return false
        }
    }

    json.optJSONArray("requiredSystemFeatures")?.asStringList()?.let { features ->
        if (features.any { !checkSystemFeatureDep(ComplexDependency(it)) }) {
            return false
        }
    }

    json.optJSONArray("staticDeps")?.asStringList()?.let { pkgDeps ->
        for (depStr in pkgDeps) {
            val dep = ComplexDependency(depStr)
            // there's currently no certificate checks for static dependencies, require them to
            // be a system package instead, unless it's our own package
            val enforceSystemPkg = dep.lhs != selfPkgName
            if (!checkPackageDep(dep, dependentManifestPkgName, repo, enforceSystemPkg)) {
                return false
            }
        }
    }

    return true
}

private fun checkSystemFeatureDep(dep: ComplexDependency): Boolean {
    val featureInfo: FeatureInfo = maybeGetSystemFeatureInfo(dep.lhs) ?: return false
    return dep.check(featureInfo.version.toLong())
}

private fun checkPackageDep(dep: ComplexDependency, dependentManifestPkgName: String,
                            repo: Repo, enforceSystemPkg: Boolean = false): Boolean {
    val manifestPackageName = dep.lhs
    val packageName = repo.translateManifestPackageName(manifestPackageName)
    val pi = pkgManager.getPackageInfoOrNull(packageName) ?: return false
    val appInfo = pi.applicationInfo ?: return false
    if (!appInfo.enabled && manifestPackageName != dependentManifestPkgName) {
        return false
    }
    if (enforceSystemPkg) {
        if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
            return false
        }
    }
    return dep.check(pi.longVersionCode)
}

private fun hexStringToByteArray(s: String): ByteArray {
    // each byte takes 2 characters, so length must be even
    require(s.length.isEven())

    val len = s.length / 2
    val arr = ByteArray(len)
    for (i in 0 until len) {
        val off = i shl 1
        val top: Int = Character.digit(s[off].code, 16)
        val bot: Int = Character.digit(s[off + 1].code, 16)
        arr[i] = ((top shl 4) or bot).toByte()
    }
    return arr
}

private fun shouldSkipRenamedPackages(): Boolean {
    val buildIncremental: Long? = Build.VERSION.INCREMENTAL.toLongOrNull()
    return buildIncremental != null
            // updates of packages that were renamed by the original-package system cause a
            // system_server crash on these versions.
            // See https://github.com/GrapheneOS/platform_frameworks_base/commit/3fd0aaea464535b683a231bd92627056c2e02518
            && buildIncremental > 2024_0303_00 && buildIncremental <= 2024_0311_00
}
