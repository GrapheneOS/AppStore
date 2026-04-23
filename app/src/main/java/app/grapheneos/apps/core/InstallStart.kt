package app.grapheneos.apps.core

import android.content.Intent
import android.net.Network
import android.util.ArraySet
import android.util.Log
import androidx.fragment.app.Fragment
import app.grapheneos.apps.Notifications
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.setContentTitle
import app.grapheneos.apps.ui.DetailsScreen
import app.grapheneos.apps.ui.ErrorDialog
import app.grapheneos.apps.util.ActivityUtils
import app.grapheneos.apps.util.checkMainThread
import app.grapheneos.apps.util.isSystemPackage
import app.grapheneos.apps.util.packageUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class InstallParams(
    // null means "use the default network"
    val network: Network?,
    val isUpdate: Boolean,
    val isUserInitiated: Boolean,
)

fun startPackageInstallFromUi(pkg: RPackage, isUpdate: Boolean, fragment: Fragment) {
    val params = InstallParams(network = null, isUpdate, isUserInitiated = true)
    try {
        @Suppress("DeferredResultUnused")
        startPackageInstall(pkg, params, fragment)
    } catch (ibe: InstallerBusyException) {
        ErrorDialog.show(fragment, ibe.details)
    } catch (dre: DependencyResolutionException) {
        ErrorDialog.show(fragment, dre.details)
    }
}

@Throws(InstallerBusyException::class, DependencyResolutionException::class)
fun startPackageInstall(pkg: RPackage, params: InstallParams,
                        callerFragment: Fragment? = null): Deferred<Deferred<PackageInstallerError?>> {
    val dependencies = getMissingDependencies(pkg, forUpdate = params.isUpdate)

    val packagesToInstall: List<RPackage> = if (dependencies.isEmpty()) {
        listOf(pkg)
    } else {
        dependencies + pkg
    }

    packagesToInstall.forEach {
        if (PackageStates.getPackageState(it.packageName).isInstalling()) {
            throw InstallerBusyException(InstallerBusyError(it.packageName, pkg.packageName))
        }
    }

    if (packagesToInstall.size == 1 && isPrivilegedInstaller && params.isUserInitiated
            && !params.isUpdate && callerFragment != null) {
        val pkgInfo = InstallTask.findPackage(pkg.packageName, pkg.versionCode, pkg.common.validCertDigests)
        if (pkgInfo != null && pkgInfo.isSystemPackage()) {
            // This is a system package that is not installed in the current user. Installing it
            // using the regular method will fail on GrapheneOS because update of a system package to
            // the same version is not allowed.
            //
            // Privileged installers can avoid this issue by using PackageInstaller#installExistingPackage()
            // which simply marks the package as installed in the specified user, but it skips the
            // installation confirmation UI.
            // Using ACTION_INSTALL_PACKAGE with package:// Uri will show the standard PackageInstaller
            // UI which will use installExistingPackage() itself.
            //
            // Note that using this approach for non-system packages is unsafe, despite the certificate
            // check above, because package may change by the time user confirms the installation.

            val uri = packageUri(pkg.packageName)

            @Suppress("DEPRECATION")
            // there's no modern equivalent for this action when it's used with package:// Uri
            val i = Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }

            // actual result is ignored, startActivityForResult() is needed only so that
            // PackageInstaller UI can use getCallingPackage() to verify that EXTRA_NOT_UNKNOWN_SOURCE
            // comes from a privileged installer
            @Suppress("DEPRECATION")
            callerFragment.startActivityForResult(i, -1)

            // PackageInstaller has its own error UI, return null PackageInstallerError to
            // the caller
            return CoroutineScope(Dispatchers.IO).async { async { null } }
        }
    }

    return startInstallTaskInner(packagesToInstall, params)
}

// Assumes that dependencies were already resolved
private fun startInstallTaskInner(pkgs: List<RPackage>, params: InstallParams,
        callbackBeforeCommit: (suspend () -> Unit)? = null): Deferred<Deferred<PackageInstallerError?>> {
    checkMainThread()

    val resConfig = appResources.configuration

    val tasks: List<InstallTask> = pkgs.map { pkg ->
        val apks = pkg.collectNeededApks(resConfig)
        InstallTask(pkg, PackageStates.getPackageState(pkg.packageName), apks, params, callbackBeforeCommit)
    }

    val isAddedToListOfBusyPackages = AtomicBoolean()
    val packageNames = pkgs.map { it.packageName }

    val deferred: Deferred<Deferred<PackageInstallerError?>> = CoroutineScope(Dispatchers.IO).async {
        updateListOfBusyPackages(true, packageNames)
        isAddedToListOfBusyPackages.set(true)

        if (tasks.size == 1) {
            tasks[0].run()
        } else {
            InstallTask.multiInstall(tasks)
        }
    }

    fun removeFromListOfBusyPackages(pkgInstallerResult: Deferred<PackageInstallerError?>?) {
        if (isAddedToListOfBusyPackages.get()) {
            CoroutineScope(Dispatchers.Default).launch {
                // packageInstallerResult will be null if installation process failed before
                // committing the PackageInstaller session
                pkgInstallerResult?.await()
                updateListOfBusyPackages(false, packageNames)
            }
        }
    }

    tasks.forEach {
        it.jobReferenceForMainThread = deferred
    }

    CoroutineScope(Dispatchers.Main).launch {
        val throwable = try {
            val pkgInstallerResult = deferred.await()
            removeFromListOfBusyPackages(pkgInstallerResult)
            null
        } catch (e: Throwable) {
            removeFromListOfBusyPackages(null)
            e
        }

        tasks.forEach {
            PackageStates.completeInstallTask(it)
        }

        if (throwable != null) {
            handleInstallTaskError(tasks, throwable)
        }
    }

    return deferred
}

private fun handleInstallTaskError(tasks: List<InstallTask>, throwable: Throwable) {
    if (tasks.any { !it.params.isUserInitiated || it.isManuallyCancelled }) {
        return
    }

    val error = if (throwable is CancellationException) {
        throwable.cause.let {
            if (it !is CancellationException) {
                it
            } else {
                return
            }
        }
    } else {
        throwable
    } ?: return

    val template = DownloadError(tasks.map { it.rPackage.label }, error)
    val pendingAction = ErrorDialog.createPendingDialog(template)

    ActivityUtils.addPendingAction(pendingAction) {
        Notifications.builder(Notifications.CH_PACKAGE_DOWLOAD_FAILED).apply {
            setSmallIcon(R.drawable.ic_error)
            setContentTitle(R.string.notif_download_failed)
            setContentText(tasks.map { it.rPackage.label }.joinToString())
            setContentIntent(DetailsScreen.createPendingIntent(tasks.last().rPackage.packageName))
        }
    }
}

fun collectOutdatedPackageGroups(): List<List<RPackage>> {
    checkMainThread()

    val rPackagesToInstall = HashMap<String, RPackage>()
    // package group is package plus its dependencies
    val packageGroups = ArrayList<ArraySet<String>?>()

    // dynamic dependencies can have only a minVersion constraint, which vastly simplifies the
    // dependency resolution process: newer version of dependency always replaces the older version
    // in the list
    fun maybeReplaceRPackage(rPackage: RPackage) {
        rPackagesToInstall[rPackage.packageName].let {
            if (it == null || it.versionCode < rPackage.versionCode) {
                rPackagesToInstall[rPackage.packageName] = rPackage
            }
        }
    }

    PackageStates.map.values.filter {
        it.isEligibleForBulkUpdate() && !it.isInstalling()
    }.forEach addPkg@{ pkgState ->
        val rPackage = pkgState.rPackage
        val pkgName = rPackage.packageName

        val group = ArraySet<String>(1 + rPackage.dependencies.size * 2)
        group.add(pkgName)

        val missingDeps = try {
            getMissingDependencies(rPackage, forUpdate = true)
        } catch (e: DependencyResolutionException) {
            mainHandler.post {
                showMissingDependencyUi(e.details)
            }
            return@addPkg
        }

        missingDeps.forEach addDep@{ depRPackage ->
            val depPkgName = depRPackage.packageName
            val depPkgState = PackageStates.getPackageState(depPkgName)

            if (depPkgState.isInstalling()) {
                return@addPkg
            }

            maybeReplaceRPackage(depRPackage)
            group.add(depPkgName)
        }
        maybeReplaceRPackage(rPackage)
        packageGroups.add(group)
    }

    // Merge package groups that have at least one package in common, to install them together,
    // atomically.
    // Algorithm is simple and somewhat inefficient, but is fast enough in practice
    while (true) {
        var mergeCount = 0

        val listSize = packageGroups.size
        for (i in 0 until listSize) {
            val group1 = packageGroups[i] ?: continue

            for (j in i + 1 until listSize) {
                val group2 = packageGroups[j] ?: continue
                val group2Size = group2.size

                for (k in 0 until group2Size) {
                    val pkg = group2.valueAt(k)

                    if (group1.contains(pkg)) {
                        group1.addAll(group2)
                        packageGroups[j] = null
                        ++mergeCount
                        break
                    }
                }
            }
        }

        if (mergeCount == 0) {
            break
        }
    }

    val rPackageGroups = ArrayList<List<RPackage>>(packageGroups.size)

    for (group in packageGroups) {
        if (group == null) { // was merged into another group
            continue
        }

        val rPackageGroup = group.map {
            rPackagesToInstall[it]!!
        }

        rPackageGroups.add(rPackageGroup)
    }

    return rPackageGroups
}

fun startPackageUpdate(params: InstallParams, allRPackageGroups: List<List<RPackage>>):
        List<Deferred<Deferred<PackageInstallerError?>>> {
    check(params.isUpdate)

    var selfUpdateGroup_: List<RPackage>? = null

    val regularGroups = allRPackageGroups.filter { group ->
        val selfPkgCount = group.count { it.packageName == selfPkgName }
        check(selfPkgCount <= 1)
        if (selfPkgCount == 1) {
            check(selfUpdateGroup_ == null)
            selfUpdateGroup_ = group
        }
        selfPkgCount == 0
    }
    // workaround for bad mutability inference in Kotlin compiler
    val selfUpdateGroup = selfUpdateGroup_

    if (params.isUserInitiated) {
        val jobs = regularGroups.map { startInstallTaskInner(it, params) }

        if (selfUpdateGroup == null) {
            return jobs
        }
        // user has explicitly started the update process, install self-update after all other updates
        // (our process will be killed by the OS during self-update)
        val selfUpdateJob = startInstallTaskInner(selfUpdateGroup, params, callbackBeforeCommit = {
            val TAG = "SelfUpdate"
            Log.d(TAG, "waiting for all updates to complete before committing self-update session")
            jobs.awaitAll().awaitAll()
            Log.d(TAG, "finished waiting")
        })

        return jobs + selfUpdateJob
    } else { // !params.isUserInitiated, ie auto-update
        if (selfUpdateGroup != null) {
            // installing self-update will kill our process and cancel all notifications about previous
            // background auto-updates (if they are present), so install it first
            val job = startInstallTaskInner(selfUpdateGroup, params)
            return listOf(job)
        }
        return regularGroups.map { startInstallTaskInner(it, params) }
    }
}

private val updateListOfBusyPackagesMethod: Method? by lazy {
    if (!isPrivilegedInstaller) {
        return@lazy null
    }

    try {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        pkgManager.javaClass.getDeclaredMethod("updateListOfBusyPackages",
            java.lang.Boolean.TYPE, java.util.List::class.java)
    } catch (_: ReflectiveOperationException) {
        null
    }
}

class PackagesBusyException(val packageNames: List<String>) : Exception()

// See https://github.com/GrapheneOS/platform_frameworks_base, file
// services/core/java/com/android/server/pm/PrivilegedInstallerHelper.java
private fun updateListOfBusyPackages(add: Boolean, packageNames: List<String>) {
    val TAG = "updateListOfBusyPackages"

    val method = updateListOfBusyPackagesMethod ?: return

    val res = method.invoke(pkgManager, add, packageNames) as Boolean

    if (add) {
        if (!res) {
            throw PackagesBusyException(packageNames)
        }
    } else {
        if (!res) {
            Log.d(TAG, "unable to remove " + packageNames.joinToString())
        }
    }
}
