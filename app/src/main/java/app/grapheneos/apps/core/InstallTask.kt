package app.grapheneos.apps.core

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.Session
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.O_CREAT
import android.system.OsConstants.O_RDONLY
import android.system.OsConstants.O_RDWR
import android.system.OsConstants.O_TRUNC
import android.system.OsConstants.SEEK_CUR
import android.system.OsConstants.S_IRUSR
import android.system.OsConstants.S_IWUSR
import android.util.Log
import app.accrescent.ina.Ina
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.core.InstallerSessions.abandonSession
import app.grapheneos.apps.util.ScopedFileDescriptor
import app.grapheneos.apps.util.checkMainThread
import app.grapheneos.apps.util.checkSha256
import app.grapheneos.apps.util.copyTo2
import app.grapheneos.apps.util.getPackageArchiveInfo
import app.grapheneos.apps.util.getPackageInfoOrNull
import app.grapheneos.apps.util.lseekToStart
import app.grapheneos.apps.util.makeTemporaryFileDescriptor
import app.grapheneos.apps.util.openConnection
import app.grapheneos.apps.util.sendfile
import app.grapheneos.apps.util.throwIfAppInstallationNotAllowed
import app.grapheneos.apps.util.throwResponseCodeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Method
import java.net.HttpURLConnection.HTTP_PARTIAL
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection.HTTP_OK

class InstallTask(
    val rPackage: RPackage,
    val packageState: PackageState, // access only from the main thread
    val apks: List<Apk>,
    val params: InstallParams,
    val callbackBeforeCommit: (suspend () -> Unit)?,
) {
    val downloadProgress = AtomicLong()
    val currentVersion = packageState.osPackageInfo?.longVersionCode
    val deltaSourceIndex: Int = rPackage.getDeltaSourceIndex(packageState)
    val installedSplits: Map<String, String> = packageState.installedSplits
    val downloadTotal = AtomicLong(rPackage.calculateDownloadSize(deltaSourceIndex, installedSplits, apks))

    init {
        checkMainThread()
        PackageStates.addInstallTask(this)
    }

    // Do not access from the main thread, it's initialized inside the coroutine, use
    // jobReferenceForMainThread instead
    private lateinit var job: Job

    // written on the main thread after the job is launched, don't access from worker threads to
    // avoid the race condition
    lateinit var jobReferenceForMainThread: Job
    var isManuallyCancelled = false

    fun cancel() {
        checkMainThread()
        jobReferenceForMainThread.cancel()
        isManuallyCancelled = true
        packageState.notifyListeners()
    }

    suspend fun run(): Deferred<PackageInstallerError?> {
        throwIfAppInstallationNotAllowed()

        job = currentCoroutineContext().job

        val sessionId = InstallerSessions.createSession(makeSessionParams(), packageState)
        var shouldAbandonSession = true
        try {
            pkgInstaller.openSession(sessionId).use { session ->
                obtainAndWriteApks(session)

                callbackBeforeCommit?.invoke()
                cancelIfPackageVanished()

                throwIfAppInstallationNotAllowed()
                job.ensureActive()

                val sessionCompletionChannel = PkgInstallerStatusReceiver.getCompletionChannelForSession(sessionId)

                session.commit(PkgInstallerStatusReceiver.getIntentSender(listOf(rPackage), params.isUserInitiated))
                shouldAbandonSession = false

                // actual installation is performed by the OS, represent this as Deferred
                val installationResult: Deferred<PackageInstallerError?> =
                    CoroutineScope(Dispatchers.Default).async {
                        sessionCompletionChannel.receive()
                    }

                return installationResult
            }
        } finally {
            if (shouldAbandonSession) {
                abandonSession(sessionId)
            }
        }
    }

    private val packageCachePruningJob: Job? = PackageStates.packageCachePruningJob

    private suspend fun obtainAndWriteApks(session: Session) {
        if (!params.isUpdate && maybeReuseAvailableApks(session)) {
            // successfully reused APKs from other user profile
        } else {
            // If there was a cache pruning job running when this install task was created, await
            // for its completion to improve reliability of apk downloads. Creation of new pruning
            // jobs is blocked when at least one installation is in progress
            packageCachePruningJob?.join()

            state = STATE_DOWNLOADING
            coroutineScope {
                apks.forEach { apk ->
                    launch {
                        val deltaBaseFd = obtainDeltaBaseFd(apk)
                        try {
                            obtainAndWriteApk(apk, session, deltaBaseFd)
                        } finally {
                            if (deltaBaseFd != null) {
                                Os.close(deltaBaseFd)
                            }
                        }
                    }
                }

                if (rPackage.hasV4Signatures) {
                    apks.forEach { apk ->
                        launch {
                            val name = "${apk.name}.idsig"
                            obtainAndWriteV4OrFsvSigSignature(name, name,
                                apk.v4SignatureSize.toLong(), apk.v4SignatureSha256!!, session)
                        }
                    }
                }

                if (rPackage.common.hasFsvSigSignatures) {
                    val certId = rPackage.common.repo.fsVerityCertificateId
                    if (certId != null) {
                        apks.forEach { apk ->
                            launch {
                                val downloadName = "${apk.name}.${certId}.fsv_sig"
                                // OS requires this format for file name that is written into session
                                val name = "${apk.name}.fsv_sig"
                                obtainAndWriteV4OrFsvSigSignature(downloadName, name,
                                    apk.fsvSignatureSize.toLong(), apk.fsvSignatureSha256!!, session,
                                    // fsv_sig signatures are small (typically under 1K) and don't compress well
                                    disableCompression = true)
                            }
                        }
                    }
                }
            }
        }
        state = STATE_PENDING_INSTALL
    }

    private fun cancelIfPackageVanished() {
        if (params.isUpdate && !rPackage.common.isSharedLibrary) {
            if (pkgManager.getPackageInfoOrNull(rPackage.packageName) == null) {
                // Package that was supposed to be updated vanished, abort installation.
                // If we have the INSTALL_PACKAGES permission and would have proceeded, we'd end up
                // installing this package again, without any confirmation by the user
                throw CancellationException()
            }
        }
    }

    private suspend fun maybeReuseAvailableApks(session: Session): Boolean {
        val pkgInfo = findPackage(rPackage.packageName, rPackage.versionCode, rPackage.common.validCertDigests)
        if (pkgInfo == null) {
            return false
        }

        val appInfo = pkgInfo.applicationInfo
        if (appInfo == null) {
            return false
        }

        coroutineScope {
            val splitSourceDirs = appInfo.splitSourceDirs ?: emptyArray<String>()
            (splitSourceDirs + appInfo.sourceDir).forEach { apkPath ->
                val apkFile = File(apkPath)
                launch {
                    val apkSize = apkFile.length()
                    apkFile.inputStream().use { input ->
                    session.openWrite(apkFile.name, 0L, apkSize).use { output ->
                        input.copyTo2(apkSize, output, job, apkFile.path)
                    }}
                }
            }
        }

        return true
    }

    private val apksDir = File(packageCacheDir, "${rPackage.common.packageName}/${rPackage.versionCode}")

    // Note that files in cache dir may be removed by the OS at any time when it is running low
    // on storage space or if another app asks to allocate storage space.
    // App process will not be restarted when cache is cleared. Cache may get cleared fully or
    // partially.
    // To handle this behavior:
    // - always call mkdirs() before creating files to handle the case when directory
    // is removed racily. There's still a small race window between return of mkdirs() and creation of file.
    // This won't help when renaming files, and link(2)/linkat(2) can't help too, because they
    // are blocked on Android.
    // - don't reopen files unless it can't be avoided (file descriptor remains valid even if its
    // file is removed)

    private fun openTempFileFd(tmpPath: String): ScopedFileDescriptor {
        apksDir.mkdirs()
        // O_TRUNC to handle stale tmp files that may remain after process kill, power loss etc
        val flags = O_RDWR or O_CREAT or O_TRUNC
        val mode = S_IRUSR or S_IWUSR
        return ScopedFileDescriptor(Os.open(tmpPath, flags, mode))
    }

    // Make sure file contents are fully synced before renaming it. Non-tmp file
    // should always have valid contents, even if it's incomplete
    private fun fsyncAndRename(tmpFd: FileDescriptor, tmpPath: String, path: String) {
        Os.fsync(tmpFd)
        try {
            Os.rename(tmpPath, path)
        } catch (e: ErrnoException) {
            // rename is used only for caching purposes, it's fine if it fails
            Log.d("fsyncAndRename", "", e)
        }
    }

    private fun obtainDeltaBaseFd(apk: Apk): FileDescriptor? {
        val deltaBasePath: String? =
            if (deltaSourceIndex >= 0 && apk.deltaSizes[deltaSourceIndex] > 0) {
                installedSplits[apk.name]
            } else {
                null
            }
        if (deltaBasePath == null) {
            return null
        }
        var result: FileDescriptor? = null
        try {
            val fd = try {
                Os.open(deltaBasePath, O_RDONLY, 0)
            } catch (e: ErrnoException) {
                Log.w("obtainDeltaBaseFd", "unable to open $deltaBasePath", e)
                return null
            }

            try {
                if (checkSha256(fd, Os.fstat(fd).st_size, apk.deltasSrcSha256[deltaSourceIndex])) {
                    lseekToStart(fd)
                    result = fd
                } else {
                    if (params.isUserInitiated) {
                        mainHandler.post {
                            packageState.ignoreDeltasForVersion(currentVersion!!)
                        }
                        throw InvalidDeltaBaseApkException("SHA-256 check failed for $deltaBasePath")
                    } else {
                        Log.w("obtainDeltaBaseFd", "SHA-256 check failed for $deltaBasePath, falling back to full APK download")
                    }
                }
            } finally {
                if (result !== fd) {
                    Os.close(fd)
                }
            }
        } finally {
            if (result == null) {
                // download will fall back to the full compressed APK
                downloadTotal.addAndGet(apk.compressedSize - apk.deltaSizes[deltaSourceIndex])
            }
        }
        return result
    }

    private suspend fun obtainAndWriteApk(apk: Apk, session: Session, deltaBaseFd: FileDescriptor?) {
        val fileName = StringBuilder(apk.name).run {
            append('.')
            if (deltaBaseFd != null) {
                append(rPackage.availableDeltaVersions[deltaSourceIndex])
                append(".ina")
            } else {
                append("br")
            }
            toString()
        }
        val file = File(apksDir, fileName)
        val path = file.path
        val tmpPath = "$path.tmp"
        val fullSize = if (deltaBaseFd != null) apk.deltaSizes[deltaSourceIndex] else apk.compressedSize

        // Check whether this apk is already partially or fully downloaded
        try {
            Os.open(path, O_RDONLY, 0)
        } catch (_: ErrnoException) {
            null
        }?.let {
            ScopedFileDescriptor(it).use { fd ->
                var curSize = Os.fstat(fd.v).st_size
                // apk is already fully downloaded
                if (curSize == fullSize) {
                    downloadProgress.getAndAdd(curSize)
                    uncompressAndWriteApk(fd.v, path, fullSize, apk, deltaBaseFd, session)
                    return
                }

                if (curSize > fullSize) {
                    Log.e("obtainAndWriteApk", "unexpected size of $path: $curSize, max expected $fullSize")
                    curSize = 0
                }

                // Copy incomplete apk into a tmp file. Could have renamed apk file into tmp file directly,
                // but this would make things more complicated and brittle, because rename and write
                // to file may get reordered before they are actually written to storage, which
                // could corrupt file's contents
                openTempFileFd(tmpPath).use { tmpFd ->
                    if (curSize > 0) {
                        sendfile(tmpFd.v, fd.v, curSize)
                        check(Os.lseek(tmpFd.v, 0L, SEEK_CUR) == curSize)
                        downloadProgress.getAndAdd(curSize)
                    }
                    try {
                        download(apk.downloadUrl(fileName), tmpFd.v, curSize, fullSize)
                    } finally {
                        fsyncAndRename(tmpFd.v, tmpPath, path)
                    }
                    uncompressAndWriteApk(tmpFd.v, path, fullSize, apk, deltaBaseFd, session)
                }
            }
            return
        }

        // cached apk not found
        openTempFileFd(tmpPath).use { tmpFd ->
            try {
                download(apk.downloadUrl(fileName), tmpFd.v, curSize = 0L, fullSize)
            } finally {
                fsyncAndRename(tmpFd.v, tmpPath, path)
            }
            uncompressAndWriteApk(tmpFd.v, path, fullSize, apk, deltaBaseFd, session)
        }
    }

    private suspend fun obtainAndWriteV4OrFsvSigSignature(downloadName: String, name: String, size: Long, sha256: ByteArray, session: Session, disableCompression: Boolean = false) {
        val logTag = "obtainApkSignature"
        val file = File(apksDir, downloadName)
        val path = file.path

        // v4 and fsv_sig signatures are usually small, skip download resume support for simplicity

        // Check whether this signature is already downloaded
        try {
            Os.open(path, O_RDONLY, 0)
        } catch (_: ErrnoException) {
            null
        }?.let {
            var written = false
            ScopedFileDescriptor(it).use { fd ->
                if (!checkSha256(fd.v, size, sha256)) {
                    Log.e(logTag, "sha256 mismatch for cached $path")
                } else {
                    lseekToStart(fd.v)
                    FileInputStream(fd.v).use { inputStream ->
                        session.openWrite(name, 0, size).use { outputStream ->
                            inputStream.copyTo2(size, outputStream, job, path)
                            written = true
                        }
                    }
                }
            }
            if (written) {
                return
            }
        }

        val tmpPath = "$path.tmp"
        openTempFileFd(tmpPath).use { tmpFd ->
            val downloadProgress = AtomicLong()

            httpDownloadSemaphore.withPermit {
                val url = "$REPO_BASE_URL/packages/${rPackage.manifestPackageName}/${rPackage.versionCode}/$downloadName"

                openConnection(params.network, url) {
                    if (disableCompression) {
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                }.use { conn ->
                    job.ensureActive()

                    if (conn.v.responseCode != HTTP_OK) {
                        throwResponseCodeException(conn.v)
                    }

                    conn.v.inputStream.use { inputStream ->
                    FileOutputStream(tmpFd.v).use { outputStream ->
                        inputStream.copyTo2(size, outputStream, job, url, downloadProgress)
                    }}
                }
            }

            lseekToStart(tmpFd.v)
            if (!checkSha256(tmpFd.v, size, sha256)) {
                throw GeneralSecurityException("sha256 mismatch for $path")
            }
            lseekToStart(tmpFd.v)

            fsyncAndRename(tmpFd.v, tmpPath, path)

            FileInputStream(tmpFd.v).use { inputStream ->
                session.openWrite(name, 0, size).use { outputStream ->
                    inputStream.copyTo2(size, outputStream, job, name)
                }
            }
        }
    }

    // file descriptor arguments are never closed by this function
    @Throws(IOException::class)
    private suspend fun inaPatch(oldFileFd: FileDescriptor, patch: FileDescriptor, new: FileDescriptor): Long {
        return inaPatcherSemaphore.withPermit {
            Ina.patch(oldFileFd, patch, new)
        }
    }

    private suspend fun uncompressAndWriteApk(
        compressedFd: FileDescriptor,
        compressedPath: String,
        compressedSize: Long,
        apk: Apk,
        deltaBaseFd: FileDescriptor?,
        session: Session
    ) {
        val compressedSha256: ByteArray = if (deltaBaseFd != null) {
            apk.deltasSha256[deltaSourceIndex]
        } else {
            apk.compressedSha256
        }

        lseekToStart(compressedFd)
        if (!checkSha256(compressedFd, compressedSize, compressedSha256)) {
            File(compressedPath).delete()
            throw GeneralSecurityException("sha256 mismatch for " +
                        "${if (deltaBaseFd != null) "delta" else "compressed"} file for ${apk.name}")
        }
        lseekToStart(compressedFd)

        makeTemporaryFileDescriptor().use { uncompressedFd ->
            if (deltaBaseFd != null) {
                try {
                    val res = inaPatch(deltaBaseFd, compressedFd, uncompressedFd.v)
                    if (res != apk.size) {
                        throw GeneralSecurityException("patched size mismatch for ${apk.name}: expected ${apk.size}, got $res")
                    }
                    lseekToStart(uncompressedFd.v)
                    if (!checkSha256(uncompressedFd.v, apk.size, apk.sha256)) {
                        throw GeneralSecurityException("sha256 mismatch for patched ${apk.name}")
                    }
                } catch (e: Exception) {
                    when (e) {
                        is ErrnoException, is GeneralSecurityException, is IOException -> {
                            mainHandler.post {
                                packageState.ignoreDeltasForVersion(currentVersion!!)
                            }
                        }
                    }
                    throw e
                }
            } else {
                val res = Ina.unbrotli(compressedFd, uncompressedFd.v)
                if (res != apk.size) {
                    throw GeneralSecurityException("uncompressed size mismatch for ${apk.name}")
                }
                lseekToStart(uncompressedFd.v)
                if (!checkSha256(uncompressedFd.v, apk.size, apk.sha256)) {
                    throw GeneralSecurityException("sha256 mismatch for uncompressed ${apk.name}")
                }
            }

            lseekToStart(uncompressedFd.v)

            if (apk.pkg.common.noCode || apk.pkg.common.isSharedLibrary) {
                // see dupToPfd comment to see why dup is even needed
                uncompressedFd.dupToPfd().use {
                    // there's no public variant of getPackageArchiveInfo() that accepts a file descriptor
                    val pkgInfo = pkgManager.getPackageArchiveInfo("/proc/self/fd/${it.getFd()}", 0L)!!

                    // enforce that packages that declare that they don't have code declare it in their
                    // AndroidManifest too. OS won't run any code from packages that have hasCode="false"
                    // directive in their manifest
                    if (apk.pkg.common.noCode) {
                        val appInfo = pkgInfo.applicationInfo
                        if (appInfo == null || (appInfo.flags and ApplicationInfo.FLAG_HAS_CODE) != 0) {
                            throw GeneralSecurityException("${apk.pkg.packageName}: ${apk.name} should have " +
                                    "hasCode=\"false\" attribute in its AndroidManifest")
                        }
                    }

                    // don't allow abusing isSharedLibrary property to bypass app install confirmation
                    // requirement
                    if (apk.pkg.common.isSharedLibrary) {
                        // there's no equivalent public API as of SDK 34
                        @SuppressLint("DiscouragedPrivateApi")
                        val privateFlagsField = ApplicationInfo::class.java.getDeclaredField("privateFlags")
                        val privateFlags = privateFlagsField.get(pkgInfo.applicationInfo) as Int
                        val PRIVATE_FLAG_STATIC_SHARED_LIBRARY = 1 shl 14
                        if ((privateFlags and PRIVATE_FLAG_STATIC_SHARED_LIBRARY) == 0) {
                            throw GeneralSecurityException("${apk.pkg.packageName}: " +
                                    "${apk.name} is not a static shared library")
                        }
                    }

                }
                lseekToStart(uncompressedFd.v)
            }

            FileInputStream(uncompressedFd.v).use { inputStream ->
            session.openWrite(apk.name, 0, apk.size).use { outputStream ->
                inputStream.copyTo2(apk.size, outputStream, job, apk.name)
            }}
        }
    }

    private suspend fun download(url: String, fd: FileDescriptor, curSize: Long, fullSize: Long) {
        check(curSize >= 0L && curSize < fullSize)

        httpDownloadSemaphore.withPermit {
            openConnection(params.network, url) {
                setRequestProperty("Accept-Encoding", "identity")
                if (curSize > 0) {
                    addRequestProperty("Range", "bytes=${curSize}-")
                }
            }.use { conn ->
                job.ensureActive()

                if (conn.v.responseCode != if (curSize == 0L) HTTP_OK else HTTP_PARTIAL) {
                    throwResponseCodeException(conn.v)
                }

                val bytesToCopy = fullSize - curSize

                conn.v.inputStream.use { input ->
                FileOutputStream(fd).use { output ->
                    input.copyTo2(bytesToCopy, output, job, url, progress = downloadProgress)
                }}
            }
        }
    }

    private fun makeSessionParams(): SessionParams {
        return SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            // PackageInstaller requires manifest package name, even if it'll be renamed by original-package system
            setAppPackageName(rPackage.manifestPackageName)
            setAppLabel(rPackage.label)

            if (params.isUpdate) {
                setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)
                setInstallScenario(PackageManager.INSTALL_SCENARIO_BULK)
            } else {
                setRequireUserAction(SessionParams.USER_ACTION_REQUIRED)
                setInstallScenario(PackageManager.INSTALL_SCENARIO_FAST)
            }

            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }

            if (Build.VERSION.SDK_INT >= 34) {
                setApplicationEnabledSettingPersistent()
                if (rPackage.common.requestUpdateOwnership) {
                    setRequestUpdateOwnership(true)
                }
            }

            setSize(apks.sumOf { it.size })
        }
    }

    @Volatile
    var state: Int = STATE_PENDING_DOWNLOAD; private set

    companion object {
        const val STATE_PENDING_DOWNLOAD = 0
        const val STATE_DOWNLOADING = 1
        const val STATE_PENDING_INSTALL = 2

        private var findPackageMethod: Method? = null
        @Volatile
        private var findPackageMethodInited = false

        val packageCacheDir = File(cacheDir, "packages")

        suspend fun multiInstall(tasks: List<InstallTask>): Deferred<PackageInstallerError?> {
            throwIfAppInstallationNotAllowed()

            val multiInstallAvailable = Build.VERSION.SDK_INT >= 33
                // confirmation UI for multi package sessions is broken before Android 13, fixed on GrapheneOS 12.1
                || (isPrivilegedInstaller && Build.VERSION.SDK_INT == 32)

            if (!multiInstallAvailable) {
                throw UnsupportedOperationException("Multi-package sessions aren't supported properly by Android 12 for unprivileged installers")
            }

            val job = currentCoroutineContext().job

            var shouldAbandonSession = true
            val parentSessionId = InstallerSessions.createMultiPackageSession()

            try {
                pkgInstaller.openSession(parentSessionId).use { parentSession ->
                    coroutineScope {
                        tasks.forEach { childTask ->
                            val childSessionParams = childTask.makeSessionParams()
                            val childSessionId = InstallerSessions.createSession(childSessionParams, childTask.packageState)
                            childTask.job = job
                            parentSession.addChildSessionId(childSessionId)
                            launch {
                                pkgInstaller.openSession(childSessionId).use { childSession ->
                                    childTask.obtainAndWriteApks(childSession)
                                }
                            }
                        }
                    }

                    tasks.forEach { it.callbackBeforeCommit?.invoke() }
                    tasks.forEach { it.cancelIfPackageVanished() }

                    job.ensureActive()
                    throwIfAppInstallationNotAllowed()

                    val sessionCompletionChannel = PkgInstallerStatusReceiver.getCompletionChannelForSession(parentSessionId)

                    parentSession.commit(PkgInstallerStatusReceiver.getIntentSender(
                        tasks.map { it.rPackage },
                        isUserInitiated = tasks.first().params.isUserInitiated)
                    )
                    shouldAbandonSession = false

                    // actual installation is performed by the OS, represent this as Deferred
                    return CoroutineScope(Dispatchers.Default).async {
                        sessionCompletionChannel.receive()
                    }
                }
            } finally {
                if (shouldAbandonSession) {
                    // abandoning parent session will abandon child sessions too
                    abandonSession(parentSessionId)
                }
            }
        }

        // Ask the PackageManager to return APKs for this package if it's already installed on this device
        // by another user, its version is >= the requested version, and its cert digests match
        fun findPackage(packageName: String, minVersion: Long, validCertDigests: Array<ByteArray>): PackageInfo? {
            if (!isPrivilegedInstaller) {
                return null
            }

            if (!findPackageMethodInited) {
                try {
                    findPackageMethod = pkgManager.javaClass.getDeclaredMethod("findPackage",
                        String::class.java, java.lang.Long.TYPE, Bundle::class.java)
                } catch (_: ReflectiveOperationException) {}

                findPackageMethodInited = true
            }

            val method = findPackageMethod
            if (method == null) {
                return null
            }

            val certDigestBundle = Bundle()
            certDigestBundle.putInt("len", validCertDigests.size)
            validCertDigests.forEachIndexed { i, arr -> certDigestBundle.putByteArray(i.toString(), arr) }

            return method.invoke(pkgManager, packageName, minVersion, certDigestBundle) as PackageInfo?
        }
    }
}
