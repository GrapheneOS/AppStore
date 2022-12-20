package app.grapheneos.apps.core

import android.util.ArraySet
import app.grapheneos.apps.Notifications
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.setContentTitle
import app.grapheneos.apps.ui.DetailsScreen
import app.grapheneos.apps.ui.ErrorDialog
import app.grapheneos.apps.util.ActivityUtils
import app.grapheneos.apps.util.checkMainThread
import app.grapheneos.apps.util.getPackageInfoOrNull
import app.grapheneos.apps.util.getSharedLibraries
import kotlin.jvm.Throws

class Dependency(string: String, repo: Repo) {
    val packageName: String
    // intentionally not supporting more complex version constraints, they are not needed in practice
    val minVersion: Long

    init {
        val i = string.indexOf(' ')
        val manifestPackageName: String
        if (i >= 0) {
            manifestPackageName = string.substring(0, i)
            minVersion = string.substring(i + 1).toLong()
        } else {
            manifestPackageName = string
            minVersion = 0L
        }
        packageName = repo.translateManifestPackageName(manifestPackageName)
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

    val visited = ArraySet<String>()
    visited.add(pkg.packageName)

    val result = ArrayList<RPackage>()
    collectDependencies(pkg.packageName, deps, skipPresent, forUpdate, visited, result)
    return result
}

private fun collectDependencies(dependant: String, dependencies: Array<Dependency>,
                                skipPresent: Boolean, forUpdate: Boolean,
                                visited: ArraySet<String>,
                                result: ArrayList<RPackage>) {
    for (dep in dependencies) {
        if (!visited.add(dep.packageName)) {
            continue
        }

        val container = PackageStates.repo.packages[dep.packageName]

        if (container == null) {
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
        val depPackage = matchingVariants.find { it.releaseChannel == preferredChannel }
            ?: matchingVariants.last()

        collectDependencies(dep.packageName, depPackage.dependencies, skipPresent, forUpdate, visited, result)

        if (skipPresent) {
            if (depPackage.common.isSharedLibrary) {
                // no public API to get info about a particular library
                val present = pkgManager.getSharedLibraries(0L).any {
                    it.name == depPackage.packageName && it.longVersion == depPackage.versionCode
                }

                if (present) {
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
                if (!pkgInfo.applicationInfo.enabled) {
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
