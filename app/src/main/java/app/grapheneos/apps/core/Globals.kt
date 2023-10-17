package app.grapheneos.apps.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import androidx.core.content.getSystemService
import app.grapheneos.apps.ApplicationImpl
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.concurrent.Executor

@SuppressLint("StaticFieldLeak") // application context is a singleton
val appContext: Context = ApplicationImpl.baseAppContext!!

val mainLooper: Looper = appContext.mainLooper
val mainThread: Thread = mainLooper.thread
val mainHandler = Handler(mainLooper)

// use only if API requires an Executor, mainHandler.post() has less overhead than mainExecutor.execute()
val mainExecutor: Executor = appContext.mainExecutor

// application Resources. Use only when component resources are not available
// (eg Activity resources may have a different configuration than app resources)
val appResources: Resources = appContext.resources
val selfPkgName: String = appContext.packageName

val pkgManager: PackageManager = appContext.packageManager
val pkgInstaller: PackageInstaller = pkgManager.packageInstaller
val isPrivilegedInstaller = appContext.checkSelfPermission(Manifest.permission.INSTALL_PACKAGES) ==
    PackageManager.PERMISSION_GRANTED

val canUpdateDisabledPackages = Build.VERSION.SDK_INT >= 34 ||
    pkgManager.hasSystemFeature("grapheneos.package_update_preserves_package_enabled_setting")

val notificationManager: NotificationManager = appContext.getSystemService()!!

val userManager: UserManager = appContext.getSystemService()!!

val localeManager: LocaleManager? = if (Build.VERSION.SDK_INT >= 33)
    appContext.getSystemService()!! else null

val filesDir: File = appContext.filesDir
val fileForTemporaryFileDescriptor = File(filesDir, "tmp_fd")
val cacheDir: File = appContext.cacheDir

// limit the number of concurrent downloads
val httpDownloadSemaphore = Semaphore(3)
