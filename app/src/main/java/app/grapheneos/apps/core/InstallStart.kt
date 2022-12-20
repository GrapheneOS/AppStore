package app.grapheneos.apps.core

import android.app.Notification
import android.text.format.Formatter
import android.util.ArraySet
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.navigation.NavDeepLinkBuilder
import app.grapheneos.apps.Notifications
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.autoupdate.AutoUpdateJob
import app.grapheneos.apps.autoupdate.AutoUpdatePrefs
import app.grapheneos.apps.setContentTitle
import app.grapheneos.apps.show
import app.grapheneos.apps.ui.DetailsScreen
import app.grapheneos.apps.ui.ErrorDialog
import app.grapheneos.apps.ui.MainActivity
import app.grapheneos.apps.util.checkMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import app.grapheneos.apps.util.ActivityUtils
import app.grapheneos.apps.util.componentName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

fun startPackageInstallFromUi(pkg: RPackage, isUpdate: Boolean, fragment: Fragment) {
    try {
        @Suppress("DeferredResultUnused")
        startPackageInstall(pkg, true, isUpdate)
    } catch (ibe: InstallerBusyException) {
        ErrorDialog.show(fragment, ibe.details)
    } catch (dre: DependencyResolutionException) {
        ErrorDialog.show(fragment, dre.details)
    }
}

@Throws(InstallerBusyException::class, DependencyResolutionException::class)
fun startPackageInstall(pkg: RPackage, isUserInitiated: Boolean, isUpdate: Boolean): Deferred<Deferred<PackageInstallerError?>> {
    val dependencies = getMissingDependencies(pkg, forUpdate = isUpdate)

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

    return startInstallTaskInner(packagesToInstall, isUpdate, isUserInitiated)
}

// Assumes that dependencies were already resolved
private fun startInstallTaskInner(pkgs: List<RPackage>, isUpdate: Boolean, isUserInitiated: Boolean, callbackBeforeCommit: (suspend () -> Unit)? = null): Deferred<Deferred<PackageInstallerError?>> {
    checkMainThread()

    val resConfig = appResources.configuration

    val tasks: List<InstallTask> = pkgs.map { pkg ->
        val apks = pkg.collectNeededApks(resConfig)
        InstallTask(pkg, PackageStates.getPackageState(pkg.packageName), apks, isUserInitiated, isUpdate, callbackBeforeCommit)
    }

    val deferred: Deferred<Deferred<PackageInstallerError?>> = CoroutineScope(Dispatchers.IO).async {
        if (tasks.size == 1) {
            tasks[0].run()
        } else {
            InstallTask.multiInstall(tasks)
        }
    }

    tasks.forEach {
        it.jobReferenceForMainThread = deferred
    }

    CoroutineScope(Dispatchers.Main).launch {
        val throwable = try {
            @Suppress("DeferredResultUnused")
            deferred.await()
            null
        } catch (e: Throwable) { e }

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
    if (tasks.any { !it.isUserInitiated || it.isManuallyCancelled }) {
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

fun updateAllPackages(isUserInitiated: Boolean): List<Deferred<Deferred<PackageInstallerError?>>> {
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
        it.isOutdated() && !it.isInstalling()
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
    var selfUpdateGroup: List<RPackage>? = null

    for (group in packageGroups) {
        if (group == null) { // was merged into another group
            continue
        }

        var hasSelfPackage = false
        val rPackageGroup = group.map {
            if (it == selfPkgName) {
                hasSelfPackage = true
            }
            rPackagesToInstall[it]!!
        }

        if (hasSelfPackage) {
            check(selfUpdateGroup == null)
            selfUpdateGroup = rPackageGroup
            continue
        }
        rPackageGroups.add(rPackageGroup)
    }

    if (rPackageGroups.isEmpty() && selfUpdateGroup == null) {
        if (!isUserInitiated) {
            AutoUpdateJob.showAllUpToDateNotification()
        }
        return emptyList()
    }

    if (!isUserInitiated) {
        if (AutoUpdatePrefs.isPackageAutoUpdateEnabled()) {
            Notifications.cancel(Notifications.ID_AUTO_UPDATE_JOB_STATUS)
        } else {
            val allRPackages = ArrayList<RPackage>()
            rPackageGroups.forEach { it.forEach { allRPackages.add(it) } }

            selfUpdateGroup?.forEach { allRPackages.add(it) }

            Notifications.builder(Notifications.CH_AUTO_UPDATE_UPDATES_AVAILABLE).apply {
                setSmallIcon(R.drawable.ic_updates_available)

                check(allRPackages.size >= 1)
                if (allRPackages.size == 1) {
                    setContentTitle(appResources.getString(R.string.notif_pkg_update_available, allRPackages[0].label))
                } else {
                    val config = appResources.configuration
                    val sumSize = allRPackages.sumOf {
                        it.collectNeededApks(config).sumOf { it.compressedSize }
                    }.let {
                        Formatter.formatShortFileSize(appContext, it)
                    }
                    setContentTitle(appResources.getString(R.string.notif_pkg_updates_available_title, sumSize))
                    setContentText(allRPackages.map { it.label }.joinToString())
                    setStyle(Notification.BigTextStyle())
                    NavDeepLinkBuilder(appContext).run {
                        setGraph(R.navigation.nav_graph)
                        setDestination(R.id.updates_screen)
                        setComponentName(componentName<MainActivity>())
                        createPendingIntent()
                    }.let {
                        setContentIntent(it)
                    }
                }
                show(Notifications.ID_AUTO_UPDATE_JOB_STATUS)
            }
            return emptyList()
        }
    }

    if (selfUpdateGroup != null && !isUserInitiated) {
        // installing self-update will kill our process and cancel all notifications about previous
        // background auto-updates (if they are present), so install it first
        val job = startInstallTaskInner(selfUpdateGroup, isUpdate = true, isUserInitiated)
        return listOf(job)
    }

    val jobs = rPackageGroups.map { packages ->
        startInstallTaskInner(packages, isUpdate = true, isUserInitiated)
    }

    if (selfUpdateGroup == null) {
        return jobs
    }

    check(isUserInitiated) // handled above
    // "update all" was trigerred from UpdatesScreen, install self-update after all other updates
    // (our process will be killed by the OS during self-update)
    val selfUpdateJob = startInstallTaskInner(selfUpdateGroup, isUpdate = true, isUserInitiated, callbackBeforeCommit = {
        Log.d("SelfUpdate", "waiting for all updates to complete before committing self-update session")
        jobs.awaitAll().awaitAll()
        Log.d("SelfUpdate", "finished waiting")
    })
    return jobs + selfUpdateJob
}
