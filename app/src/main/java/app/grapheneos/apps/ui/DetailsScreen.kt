package app.grapheneos.apps.ui

import android.app.PendingIntent
import android.content.pm.VersionedPackage
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.navigation.NavDeepLinkBuilder
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.core.DependencyResolutionException
import app.grapheneos.apps.core.PackageState
import app.grapheneos.apps.core.PkgInstallerStatusReceiver
import app.grapheneos.apps.core.RPackage
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.getAllDependencies
import app.grapheneos.apps.core.pkgInstaller
import app.grapheneos.apps.core.pkgManager
import app.grapheneos.apps.core.selfPkgName
import app.grapheneos.apps.core.showMissingDependencyUi
import app.grapheneos.apps.core.startPackageInstallFromUi
import app.grapheneos.apps.databinding.DetailsScreenBinding
import app.grapheneos.apps.databinding.PackageListItemBinding
import app.grapheneos.apps.util.appDetailsIntent
import app.grapheneos.apps.util.componentName
import app.grapheneos.apps.util.getVersionNameOrVersionCode
import app.grapheneos.apps.util.isSystemPackage
import app.grapheneos.apps.util.isUpdatedSystemPackage
import app.grapheneos.apps.util.maybeSetText
import app.grapheneos.apps.util.setAvailable

class DetailsScreen : ViewBindingFragment<DetailsScreenBinding>(), MenuProvider {
    private lateinit var pkgState: PackageState
    private lateinit var pkgName: String

    private lateinit var dependencyAdapter: PackageListAdapter

    override fun inflate(inflater: LayoutInflater, container: ViewGroup?, attach: Boolean) =
        DetailsScreenBinding.inflate(inflater, container, attach)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PackageStates.addListener(this, object : PackageStates.StateListener {
            override fun onPackageStateChanged(state: PackageState) {
                if (state === pkgState) {
                    bindViews(state)
                }
                dependencyAdapter.updateItem(state)
            }

            override fun onAllPackageStatesChanged(states: Map<String, PackageState>) {
                val state = states[pkgName]
                if (state == null) {
                    findNavController().popBackStack()
                    return
                }
                onPackageStateChanged(state)
            }
        })
    }

    private lateinit var itemView: PackageListItemBinding

    override fun onViewsCreated(views: DetailsScreenBinding, savedInstanceState: Bundle?) {
        pkgName = navArgs<DetailsScreenArgs>().value.pkgName

        val pkgState = PackageStates.maybeGetPackageState(pkgName)
        if (pkgState == null) {
            findNavController().popBackStack()
            return
        }
        this.pkgState = pkgState

        val item = PackageListItemBinding.inflate(layoutInflater, views.pkgItemHolder, true)
        item.set(this, pkgState)
        item.root.isClickable = false
        itemView = item

        val dependencyAdapter = PackageListAdapter(this)
        this.dependencyAdapter = dependencyAdapter
        dependencyAdapter.setupRecyclerView(views.dependenciesList, setupWindowInsetsListener = false)

        ViewCompat.setOnApplyWindowInsetsListener(views.detailsScreenInnerLayout) { v, insets ->
            val paddingInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = paddingInsets.bottom)
            insets
        }
    }

    private fun bindViews(pkgState: PackageState) {
        val views = views()
        val status = pkgState.status()
        setupButtons(status)

        views.apply {
            val rPackage = pkgState.rPackage

            itemView.set(this@DetailsScreen, pkgState)

            val prevRPackage = views.root.tag as RPackage?
            if (rPackage !== prevRPackage) {
                views.root.tag = rPackage

                val dependencies = try {
                    getAllDependencies(rPackage)
                } catch (e: DependencyResolutionException) {
                    showMissingDependencyUi(e.details)
                    emptyList()
                }

                val hasDeps = dependencies.isNotEmpty()

                dependenciesLabel.isVisible = hasDeps
                dependenciesList.isVisible = hasDeps
                if (hasDeps) {
                    val deps = dependencies.map { PackageStates.getPackageState(it.packageName) }
                    dependencyAdapter.updateList(deps)
                }
            }

            var hideLatestVersionText = false

            pkgState.osPackageInfo.let {
                if (it != null) {
                    installedVersion.maybeSetText(it.versionName ?: it.longVersionCode.toString())
                    hideLatestVersionText = pkgState.rPackage.versionCode == it.longVersionCode
                }
                installedVersionLabel.isVisible = it != null
                installedVersion.isVisible = it != null
            }

            availableVersion.isGone = hideLatestVersionText
            availableVersionLabel.isGone = hideLatestVersionText

            if (!hideLatestVersionText) {
                availableVersion.maybeSetText(rPackage.versionName)
            }

            rPackage.description.let {
                pkgDescriptionLabel.isVisible = it != null
                pkgDescription.isVisible = it != null
                if (it != null) {
                    setTextHtml(pkgDescription, it)
                }
            }

            rPackage.releaseNotes.let {
                releaseNotesLabel.isVisible = it != null
                releaseNotes.isVisible = it != null
                if (it != null) {
                    setTextHtml(releaseNotes, it)
                }
            }
        }
    }

    private fun setTextHtml(v: TextView, string: String) {
        if (v.tag === string) {
            return
        }
        val flags = Html.FROM_HTML_MODE_COMPACT or
                Html.FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH or
                Html.FROM_HTML_SEPARATOR_LINE_BREAK_HEADING

        val s: Spanned = Html.fromHtml(string, flags)
        v.setText(s)
        v.tag = string
    }

    private var prevStatus: PackageState.Status? = null

    fun setupButtons(status: PackageState.Status) {
        if (status == prevStatus) {
            return
        }
        val views = views()
        val secondary = views.secondaryActionBtn
        when (status) {
            PackageState.Status.NOT_INSTALLED,
            PackageState.Status.DISABLED ->
                secondary.isGone = true

            PackageState.Status.OUT_OF_DATE,
            PackageState.Status.UP_TO_DATE -> {
                if (!MainActivity.lanchedFromSuW) {
                    setAction(secondary, R.string.settings) {
                        startActivity(appDetailsIntent(pkgName))
                    }
                } else {
                    secondary.isGone = true
                }
            }
            else -> {}
        }
        val primary = views.primaryActionBtn
        val text = views.text
        when (status) {
            PackageState.Status.NOT_INSTALLED -> {
                setAction(primary, R.string.btn_install) {
                    startPackageInstallFromUi(pkgState.rPackage, isUpdate = false, this)
                }
            }
            PackageState.Status.OUT_OF_DATE -> {
                setAction(primary, R.string.btn_update) {
                    startPackageInstallFromUi(pkgState.rPackage, isUpdate = true, this)
                }
            }
            PackageState.Status.DISABLED -> {
                setAction(primary, R.string.btn_enable_package) {
                    startActivity(appDetailsIntent(pkgName))
                }
            }
            PackageState.Status.INSTALLING -> {
                val task = pkgState.installTask
                setAction(secondary, R.string.cancel) {
                    task?.cancel()
                }
                // TODO: installer session in "pending user action" state may get stuck if the
                //  OS confirmation UI fails to update its state. Allow cancelling such sessions.
                secondary.isEnabled = task != null && !task.jobReferenceForMainThread.isCancelled
                primary.isGone = true
                secondary.isGone = false
            }
            PackageState.Status.UP_TO_DATE -> {
                val intent = pkgManager.getLaunchIntentForPackage(pkgName)
                if (intent != null && pkgName != selfPkgName && !MainActivity.lanchedFromSuW) {
                    setAction(primary, R.string.btn_open) {
                        startActivity(intent)
                    }
                } else {
                    if (MainActivity.lanchedFromSuW) {
                        text.isGone = false
                    }
                    primary.isGone = true
                }
            }
            PackageState.Status.SHARED_LIBRARY -> {
                secondary.isGone = true
                primary.isGone = true
            }
        }
    }

    private fun setAction(btn: Button, text: Int, action: Runnable) {
        btn.isEnabled = true
        btn.isVisible = true
        btn.setText(text)
        btn.setOnClickListener {
            action.run()
        }
    }

    private fun uninstall() {
        val pkgInfo = pkgState.osPackageInfo
        if (pkgInfo == null) {
            // already uninstalled, ignore UI race
            return
        }

        // PackageInstaller ignores version code passed in VersionPackage by most regular callers
        // as of Android 13 QPR1 and may uninstall the wrong version. This may happen if uninstall
        // request is made when the package is being upgraded.
        // In that case, request will stall until the package has completed upgrading and then will
        // uninstall the wrong version.
        val versionedPackage = VersionedPackage(pkgState.pkgName, pkgInfo.longVersionCode)

        val intentSender = PkgInstallerStatusReceiver.getIntentSenderForUninstall(pkgName,
            pkgState.rPackage.label, pkgInfo.getVersionNameOrVersionCode())

        pkgInstaller.uninstall(versionedPackage, intentSender)
    }

    override val menuXml = R.menu.details_screen_menu

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.uninstall -> {
                uninstall()
                true
            }
            R.id.release_channel_dialog -> {
                findNavController().navigate(
                    DetailsScreenDirections.actionDetailsScreenToReleaseChannelDialog(pkgState.pkgName))
                true
            }
            else -> super.onMenuItemSelected(menuItem)
        }
    }

    override fun onPrepareMenu(menu: Menu) {
        val pkgInfo = pkgState.osPackageInfo

        menu.forEach { menuItem ->
            when (menuItem.itemId) {
                R.id.uninstall -> {
                    var available = false
                    if (pkgInfo != null) {
                        available = !pkgInfo.isSystemPackage() || pkgInfo.isUpdatedSystemPackage()
                        if (pkgInfo.isUpdatedSystemPackage()) {
                            menuItem.setTitle(R.string.menu_item_uninstall_updates)
                        }
                    }
                    menuItem.setAvailable(available)
                }
            }
        }
    }

    companion object {
        fun createPendingIntent(pkgName: String): PendingIntent =
            NavDeepLinkBuilder(appContext).run {
                setGraph(R.navigation.nav_graph)
                setDestination(R.id.details_screen, DetailsScreenArgs.Builder(pkgName).build().toBundle())
                setComponentName(componentName<MainActivity>())
                createPendingIntent()
            }
    }
}
