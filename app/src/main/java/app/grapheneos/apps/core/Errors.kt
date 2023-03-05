package app.grapheneos.apps.core

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Parcelable
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.util.UserRestrictionException
import app.grapheneos.apps.util.appendRes
import app.grapheneos.apps.util.checkMainThread
import kotlinx.parcelize.Parcelize
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

abstract class ErrorTemplate : Parcelable {
    open fun titleResource() = 0

    open fun title(ctx: Context): CharSequence {
        val res = titleResource()
        check(res != 0)
        return ctx.getText(res)
    }

    abstract fun message(ctx: Context): CharSequence

    protected fun appendDetailsPrefix(ctx: Context, b: StringBuilder) {
        if (b.length != 0) {
            b.append("\n\n")
        }
        b.appendRes(ctx, R.string.error_details_prefix)
    }
}

abstract class InstallErrorBase : ErrorTemplate() {
    abstract fun pkgInstallerError(): PackageInstallerError

    abstract fun msgPrefix(): Int

    open fun uiErrorMessage(ctx: Context): String? = null

    open fun shouldSkipDetails(): Boolean = false

    override fun message(ctx: Context) = StringBuilder().run {
        val pie = pkgInstallerError()
        append(ctx.getString(msgPrefix(), pie.request.packages.map { it.label }.joinToString()))
        uiErrorMessage(ctx)?.let {
            append(": ")
            append(it)
        }
        append('.')
        if (!shouldSkipDetails()) {
            appendDetailsPrefix(ctx, this)
            append(pie.message)
        }
        toString()
    }
}

@Parcelize
class InstallError(val pie: PackageInstallerError) : InstallErrorBase() {
    override fun pkgInstallerError() = pie
    override fun titleResource() = R.string.installation_failed
    override fun msgPrefix() = R.string.msg_unable_to_install_pkg

    override fun uiErrorMessage(ctx: Context): String? {
        val res = if (pie.coarseStatus == PackageInstaller.STATUS_FAILURE_CONFLICT) {
            R.string.installer_error_conflict
        } else if (pie.status == PackageManagerConstants.INSTALL_FAILED_VERSION_DOWNGRADE) {
            R.string.installer_error_version_downgrade
        } else if (pie.status == PackageManagerConstants.INSTALL_FAILED_INSUFFICIENT_STORAGE) {
            R.string.installer_error_insufficient_storage
        } else {
            return null
        }
        return ctx.getString(res)
    }
}

@Parcelize
class UninstallError(val pie: PackageInstallerError) : InstallErrorBase() {
    override fun pkgInstallerError() = pie
    override fun titleResource() = R.string.uninstall_failed
    override fun msgPrefix() = R.string.msg_unable_to_uninstall_pkg

    override fun uiErrorMessage(ctx: Context): String? {
        return when (pie.status) {
            PackageManagerConstants.DELETE_FAILED_USER_RESTRICTED ->
                ctx.getString(R.string.err_msg_uninstall_restricted)
            else -> null
        }
    }

    override fun shouldSkipDetails(): Boolean {
        return pie.status == PackageManagerConstants.DELETE_FAILED_USER_RESTRICTED
    }
}

private fun networkErrorResStringOrZero(e: Throwable) = when (e) {
    is ConnectException -> R.string.network_error_unable_to_connect_to_server
    is SocketTimeoutException -> R.string.network_error_connection_timed_out
    is UnknownHostException -> R.string.network_error_unknown_host_name
    else -> 0
}

@Parcelize
class RepoUpdateError(val throwable: Throwable, val wasUpdateManuallyRequested: Boolean) : ErrorTemplate() {
    override fun titleResource() = R.string.unable_to_fetch_app_list

    fun isNotable() = when (throwable) {
        is ConnectException,
        is SocketTimeoutException,
        is UnknownHostException ->
            false
        else -> true
    }

    override fun message(ctx: Context) = StringBuilder().run {
        val resource = networkErrorResStringOrZero(throwable)
        if (resource != 0) {
            appendRes(ctx, resource)
            append('.')
        }
        appendDetailsPrefix(ctx, this)
        append(throwable.toString())
        toString()
    }
}

// All errors that happen before the installer session is committed are considered to be download
// errors, "download" means "download into PackageInstaller session", not just "download from the
// network"
@Parcelize
class DownloadError(val pkgLabels: List<String>, val throwable: Throwable) : ErrorTemplate() {
    override fun titleResource() = R.string.notif_download_failed

    private fun localizedDetails(): String? {
        when (throwable) {
            is UserRestrictionException ->
                return appResources.getString(R.string.download_error_user_restriction)
            is PackagesBusyException ->
                return appResources.getQuantityString(R.plurals.download_error_packages_busy,
                    throwable.packageNames.size)
        }
        return null
    }

    override fun message(ctx: Context) = StringBuilder().run {
        append(ctx.getString(R.string.msg_unable_to_download_pkg, pkgLabels.joinToString()))
        localizedDetails()?.also {
            append(": ")
            append(it)
        } ?: networkErrorResStringOrZero(throwable).let {
            if (it != 0) {
                append(": ")
                appendRes(ctx, it)
            }
        }
        append('.')

        if (throwable !is UserRestrictionException && throwable !is PackagesBusyException) {
            appendDetailsPrefix(ctx, this)
            append(throwable.toString())
        }
        toString()
    }
}

@Parcelize
class InstallerBusyError(val pkgName: String, val requestedPkgName: String) : ErrorTemplate() {
    override fun titleResource() = if (pkgName == requestedPkgName)
        R.string.err_title_pkg_is_installing
    else
        R.string.err_title_dep_is_installing

    override fun message(ctx: Context) =
        ctx.getString(R.string.err_msg_pkg_is_installing,
            PackageStates.maybeGetPackageLabel(pkgName) ?: pkgName)
}

@Parcelize
class MissingDependencyError(val dependantPkgName: String, val dependencyPkgName: String,
                             val dependencyMinVersion: Long, val reason: Int) : ErrorTemplate() {

    constructor(dependant: String, dep: Dependency, reason: Int) :
            this(dependant, dep.packageName, dep.minVersion, reason)

    companion object {
        const val REASON_MISSING_IN_REPO = 0
        const val REASON_DEPENDENCY_DISABLED_BEFORE_INSTALL = 1
        const val REASON_DEPENDENCY_DISABLED_AFTER_INSTALL = 2
        const val REASON_DEPENDENCY_UNINSTALLED_AFTER_INSTALL = 3
    }

    override fun titleResource() = R.string.err_title_missing_dependency

    override fun message(ctx: Context): CharSequence {
        checkMainThread()
        val dependant = PackageStates.maybeGetPackageLabel(dependantPkgName) ?: dependantPkgName
        val dependency = (PackageStates.maybeGetPackageLabel(dependencyPkgName) ?: dependencyPkgName) +
                (if (dependencyMinVersion != 0L) " >= $dependencyMinVersion" else "")

        return when (reason) {
            REASON_MISSING_IN_REPO ->
                ctx.getString(R.string.err_msg_dependency_resolution_dep_missing_in_repo,
                    dependant, dependency)
            REASON_DEPENDENCY_DISABLED_BEFORE_INSTALL,
            REASON_DEPENDENCY_DISABLED_AFTER_INSTALL ->
                ctx.getString(R.string.err_msg_dependency_resolution_dep_disabled, dependant, dependency)
            REASON_DEPENDENCY_UNINSTALLED_AFTER_INSTALL ->
                ctx.getString(R.string.err_msg_dependency_resolution_dep_not_installed, dependant, dependency)
            else -> error("")
        }
    }
}
