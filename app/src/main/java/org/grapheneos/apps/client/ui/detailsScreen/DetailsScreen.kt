package org.grapheneos.apps.client.ui.detailsScreen

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.ui.onNavDestinationSelected
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.transition.platform.MaterialContainerTransform
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.DetailsScreenBinding
import org.grapheneos.apps.client.item.DownloadStatus
import org.grapheneos.apps.client.item.InstallStatus
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.item.PackageVariant
import org.grapheneos.apps.client.ui.detailsScreen.DependencyAdapter.Companion.toSizeInfo
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.utils.AppSourceHelper
import org.grapheneos.apps.client.utils.PackageManagerHelper.Companion.pmHelper
import org.grapheneos.apps.client.utils.showSnackbar
import kotlin.collections.set
import kotlin.math.roundToInt

class DetailsScreen : Fragment() {

    private lateinit var binding: DetailsScreenBinding
    private val info by lazy {
        navArgs<DetailsScreenArgs>().value
    }
    private val app: App by lazy {
        requireContext().applicationContext as App
    }
    private val dependencyAdapter by lazy {
        DependencyAdapter()
    }
    private var isInstalled = false
    private var pkgInfo: PackageInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            scrimColor = Color.TRANSPARENT
            drawingViewId = R.id.container
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DetailsScreenBinding.inflate(inflater, container, false)
        return binding.root
    }


    fun Menu.prepareOptionsMenu() {

        val mutableItems = mutableListOf<String>()
        pkgInfo?.allVariant?.forEach { variant: PackageVariant ->
            mutableItems.add(variant.type)
        }
        forEach {
            when (it.itemId) {
                R.id.uninstall -> {
                    it.isEnabled = isInstalled

                    pkgInfo?.selectedVariant?.let { selVariant ->
                        if (app.pmHelper().isSystemApp(selVariant)) {
                            it.setTitle(R.string.uninstall_updates)
                        }
                    }
                }
                R.id.appInfo -> {
                    it.isEnabled = isInstalled
                }
                R.id.switchChannel -> {
                    it.isVisible = mutableItems.size > 1
                }
            }
        }
    }

    fun MenuItem.itemSelected(): Boolean {
        return when (itemId) {
            R.id.uninstall -> {
                app.uninstallPackage(info.pkgName)
                true
            }
            R.id.appInfo -> {
                app.openAppDetails(info.pkgName)
                true
            }
            R.id.switchChannel -> {
                val packageInfo = pkgInfo ?: return false
                findNavController().navigate(
                    DetailsScreenDirections.actionDetailsScreenToSwitchChannel(
                        packageInfo.selectedVariant.pkgName,
                        packageInfo.allVariant.map {
                            it.type
                        }.toTypedArray(),
                        packageInfo.selectedVariant.type
                    )
                )

                true
            }
            else -> onNavDestinationSelected(findNavController())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, inset ->

            val paddingInsets = inset.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = paddingInsets.bottom
            }

            inset
        }

        (requireActivity() as MenuHost).addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.details_screen_menu, menu)
                }

                override fun onPrepareMenu(menu: Menu) {
                    super.onPrepareMenu(menu)
                    menu.prepareOptionsMenu()
                }

                override fun onMenuItemSelected(menuItem: MenuItem) = menuItem.itemSelected()
            },
            viewLifecycleOwner
        )

        app.packageLiveData.observe(viewLifecycleOwner) { data ->
            val appData = data[info.pkgName]
            pkgInfo = appData
            if (appData == null) {
                findNavController().popBackStack()
            } else {
                bindViews(appData)
                val packages = appData.selectedVariant.dependencies
                val packageList = mutableMapOf<String, PackageInfo>()
                packages.forEach { pkgName ->
                    data[pkgName]?.let { pkgInfo ->
                        packageList[pkgName] = pkgInfo
                    }
                }
                dependencyAdapter.submitList(InstallablePackageInfo.fromMap(packageList))
                isInstalled = appData.installStatus.installedVersion != null
            }
        }

        binding.apply {
            dependencyRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = dependencyAdapter
                itemAnimator = DefaultItemAnimator()
            }
            install.setOnClickListener {
                app.handleOnClick(info.pkgName) {
                    showSnackbar(it)
                }
            }
            cancelButton.setOnClickListener {
                app.cancelDownload(info.pkgName)
            }
        }

    }

    private fun bindViews(packageInfo: PackageInfo) {
        val variant = packageInfo.selectedVariant
        val downloadInfo = packageInfo.downloadStatus
        val isDownloading = downloadInfo != null && downloadInfo.isDownloading
        val progressSizeInfo =
            if (downloadInfo is DownloadStatus.Downloading) downloadInfo.toSizeInfo(false) else ""
        val progress =
            if (downloadInfo is DownloadStatus.Downloading) downloadInfo.downloadedPercent.roundToInt() else 0
        binding.apply {
            install.isVisible = !isDownloading
            progressBar.isVisible = isDownloading
            progressSize.isVisible = isDownloading
            downloadPercentInfo.isVisible = isDownloading
            appDependencyLabel.isVisible = variant.dependencies.isNotEmpty()
            dependencyRecyclerView.isVisible = variant.dependencies.isNotEmpty()

            appName.text = variant.appName
            publisher.text = AppSourceHelper.getCategoryName(variant.pkgName)
            install.isEnabled = !isDownloading
            install.text = packageInfo.installStatus()

            packageInfo.installStatus.installedVersion.let {
                if (it != null) {
                    installed.text = it.toString()
                }
                val vis = if (it != null) View.VISIBLE else View.INVISIBLE
                installedLabel.visibility = vis
                installed.visibility = vis
            }

            latest.text = packageInfo.selectedVariant.versionCode.toString()
            progressBar.progress = progress
            progressSize.text = progressSizeInfo
            downloadPercentInfo.text = String.format("%s %%", progress)
            releaseTag.isVisible = "stable" != variant.type
            releaseTag.text = variant.type
        }
    }

    private fun PackageInfo.installStatus(): String {
        if (downloadStatus != null) return downloadStatus.status
        if (this.installStatus is InstallStatus.Installable && selectedVariant.dependencies.isNotEmpty()) {
            return App.getString(R.string.install_all)
        }
        return installStatus.status
    }

}
