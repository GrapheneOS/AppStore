package app.grapheneos.apps.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.core.content.edit
import app.grapheneos.apps.core.InstallTask.Companion.packageCacheDir
import app.grapheneos.apps.util.InternalSettings
import app.grapheneos.apps.util.getPackageInfoOrNull
import app.grapheneos.apps.util.megabytes
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

fun prunePackageCache() {
    maybeDeleteV1Files()

    val cacheDir = packageCacheDir
    packageCacheDir.listFiles()?.forEach { pkgDir ->
        val curVersion = pkgManager.getPackageInfoOrNull(pkgDir.name)?.longVersionCode ?: return@forEach

        pkgDir.listFiles()?.forEach { pkgVersionDir ->
            if (pkgVersionDir.name.toLong() <= curVersion) {
                pkgVersionDir.deleteRecursively()
            }
        }
    }

    val dirs: MutableList<PackageCache> = collectPackageCacheStats(cacheDir)

    dirs.sortBy { it.mtimeSeconds }

    val minMtimeSeconds = (System.currentTimeMillis().milliseconds - 2.days).inWholeSeconds
    // note that the OS may prune the cache itself at any time, to any size
    val maxCacheSize = 500.megabytes

    var totalSize = dirs.sumOf { it.sumSize }

    dirs.takeWhile {
        val mtimeSeconds = it.mtimeSeconds
        val size = it.sumSize

        if (mtimeSeconds < minMtimeSeconds) {
            totalSize -= size
        } else {
            if (totalSize < maxCacheSize) {
                return@takeWhile false
            }
            totalSize -= size
        }
        true
    }.forEach {
        it.file.deleteRecursively()
    }

    removeEmptyDirs(cacheDir)
}

private class PackageCache(
    val file: File,
    val sumSize: Long,
    val mtimeSeconds: Long,
)

private fun collectPackageCacheStats(dir: File): MutableList<PackageCache> {
    val list = mutableListOf<PackageCache>()

    dir.listFiles()?.forEach { pkgDir ->
        pkgDir.listFiles()?.forEach { pkgVersionDir ->
            var sumSize = 0L
            var maxMtimeSec = 0L
            pkgVersionDir.walk().forEach {
                val stat = statOrNull(it.path)

                if (stat != null && OsConstants.S_ISREG(stat.st_mode)) {
                    sumSize += stat.st_size
                    maxMtimeSec = maxOf(maxMtimeSec, stat.st_mtim.tv_sec)
                }
            }

            list.add(PackageCache(pkgVersionDir, sumSize, maxMtimeSec))
        }
    }

    return list
}

private fun removeEmptyDirs(dir: File) {
    dir.walkBottomUp().onEnter { subDir ->
        subDir.delete()
        return@onEnter true
    }.count()
}

private fun maybeDeleteV1Files() {
    if (InternalSettings.file.getBoolean(InternalSettings.KEY_DELETED_v1_FILES, false)) {
        return
    }
    File(filesDir, "verified").deleteRecursively()
    File(filesDir, "downloads").deleteRecursively()
    File(cacheDir, "temporary").deleteRecursively()
    File(appContext.dataDir, "shared_prefs/metadata.xml").delete()

    InternalSettings.file.edit {
        putBoolean(InternalSettings.KEY_DELETED_v1_FILES, true)
    }
}

private fun statOrNull(path: String): StructStat? {
    return try {
        Os.stat(path)
    } catch (e: ErrnoException) {
        null
    }
}
