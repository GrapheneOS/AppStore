package app.grapheneos.apps.core

import android.util.ArraySet
import android.util.Log
import app.grapheneos.apps.Notifications
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.setContentTitle
import app.grapheneos.apps.ui.DetailsScreen
import app.grapheneos.apps.ui.ErrorDialog
import app.grapheneos.apps.util.ActivityUtils
import app.grapheneos.apps.util.checkMainThread
import app.grapheneos.apps.util.getSharedLibraries
import app.grapheneos.apps.util.hasSystemFeature
import kotlin.jvm.Throws

class Dependency(string: String, repo: Repo) {
    val packageName: String
    // intentionally not supporting more complex version constraints, they are not needed in practice
    val minVersion: Long
    val flags: Set<Flag>

    enum class Flag {
        // Whether to ignore this dependency if it's missing from the parsed repo. Useful for
        // optional dependencies that are filtered out from the repo during parsing due to static
        // constraints (staticDeps, minSdk, etc).
        SkipIfMissing,
    }

    init {
        val parts = string.split(' ')

        val manifestPackageName = parts[0]
        packageName = repo.translateManifestPackageName(manifestPackageName)
        minVersion = if (parts.size > 1) parts[1].toLong() else 0L
        flags = if (parts.size <= 2) {
            emptySet()
        } else {
            val flagStrings = parts[2].split(",")
            val set = ArraySet<Flag>(flagStrings.size)
            for (flagStr in flagStrings) {
                val flag = try {
                    Flag.valueOf(flagStr)
                } catch (e: IllegalArgumentException) {
                    // allow unknown flags for backwards compatibility
                    Log.d("Dependency", "unknown flag $flagStr", e)
                    continue
                }
                set.add(flag)
            }
            set
        }
    }
}

@Throws(DependencyResolutionException::class)
fun getMissingDependencies(pkg: RPackage, forUpdate: Boolean) = getDependencies(pkg, true, forUpdate)

@Throws(DependencyResolutionException::class)
fun getAllDependencies(pkg: RPackage) = getDependencies(pkg, false, false)

private fun getDependencies(pkg: RPackage, skipPresent: Boolean, forUpdate: Boolean): List<RPackage> {
    checkMainThread()
    val deps = pkg.dependencies
    if (deps.isEmpty()) {
        return emptyList()
    }

    var requireEnabled = true
    if (forUpdate) {
        val maybePkgState = PackageStates.maybeGetPackageState(pkg.packageName)
        if (maybePkgState?.osPackageInfo?.applicationInfo?.enabled == false) {
            // allow dependencies of disabled package to be disabled too
            requireEnabled = false
        }
    }

    val visited = ArraySet<String>()
    visited.add(pkg.packageName)

    val result = ArrayList<RPackage>()
    collectDependencies(pkg.packageName, deps, skipPresent, forUpdate, requireEnabled, visited, result)
    return result
}

private fun collectDependencies(dependant: String, dependencies: Array<Dependency>,
                                skipPresent: Boolean, forUpdate: Boolean,
                                requireEnabled: Boolean,
                                visited: ArraySet<String>,
                                result: ArrayList<RPackage>) {
    for (dep in dependencies) {
        if (!visited.add(dep.packageName)) {
            continue
        }

        val container = PackageStates.repo.packages[dep.packageName]

        if (container == null) {
            if (dep.flags.contains(Dependency.Flag.SkipIfMissing)) {
                continue
            }

            val err = MissingDependencyError(dependant, dep,
                MissingDependencyError.REASON_MISSING_IN_REPO)
            throw DependencyResolutionException(err)
        }

        val matchingVariants = container.variants.filter {
            it.versionCode >= dep.minVersion
        }

        if (matchingVariants.isEmpty()) {
            val err = MissingDependencyError(dependant, dep,
                MissingDependencyError.REASON_MISSING_IN_REPO)
            throw DependencyResolutionException(err)
        }

        val preferredChannel = PackageStates.getPackageState(dep.packageName).preferredReleaseChannel()
        val depPackage = findRPackage(matchingVariants, preferredChannel)

        collectDependencies(dep.packageName, depPackage.dependencies, skipPresent, forUpdate, requireEnabled, visited, result)

        if (skipPresent) {
            if (depPackage.common.isSharedLibrary) {
                val flags: Long = if (canUseMatchAnyUserForSharedLibs()) {
                    // MATCH_ANY_USER is required to properly handle shared library updates in
                    // multi-user scenarios, since installing the same version of library for the
                    // second time is not allowed by the OS.
                    0x00400000L // PackageManager.MATCH_ANY_USER, not a part of public API
                } else {
                    0L
                }

                // no public API to get info about a particular library
                val isPresent = pkgManager.getSharedLibraries(flags).any {
                    val pkg = it.declaringPackage
                    pkg.packageName == depPackage.packageName && pkg.longVersionCode == depPackage.versionCode
                }

                if (isPresent) {
                    continue
                }
            }

            val pkgInfo = PackageStates.maybeGetPackageState(dep.packageName)?.osPackageInfo

            if (pkgInfo == null) {
                if (forUpdate && !depPackage.common.isSharedLibrary) {
                    val err = MissingDependencyError(dependant, dep,
                        MissingDependencyError.REASON_DEPENDENCY_UNINSTALLED_AFTER_INSTALL)
                    throw DependencyResolutionException(err)
                }
            } else {
                if (requireEnabled && !pkgInfo.applicationInfo.enabled) {
                    val err = MissingDependencyError(dependant, dep,
                        if (forUpdate)
                            MissingDependencyError.REASON_DEPENDENCY_DISABLED_AFTER_INSTALL
                        else
                            MissingDependencyError.REASON_DEPENDENCY_DISABLED_BEFORE_INSTALL
                        )
                    throw DependencyResolutionException(err)
                }

                if (pkgInfo.longVersionCode >= depPackage.versionCode) {
                    continue
                }
            }
        }

        result.add(depPackage)
    }
}

class DependencyResolutionException(val details: MissingDependencyError) : Exception()

fun showMissingDependencyUi(err: MissingDependencyError) {
    checkMainThread()
    val pa = ErrorDialog.createPendingDialog(err)

    ActivityUtils.addPendingAction(pa) {
        Notifications.builder(Notifications.CH_MISSING_DEPENDENCY).apply {
            setSmallIcon(R.drawable.ic_error)
            setContentTitle(R.string.notif_missing_dependency)
            setContentText(err.message(appContext))
            setContentIntent(DetailsScreen.createPendingIntent(err.dependantPkgName))
        }
    }
}

private fun canUseMatchAnyUserForSharedLibs(): Boolean {
    if (!isPrivilegedInstaller) {
        return false
    }
    val featureName = "grapheneos.priv_installer_can_use_getSharedLibraries_MATCH_ANY_USER"
    return hasSystemFeature(featureName)
}
