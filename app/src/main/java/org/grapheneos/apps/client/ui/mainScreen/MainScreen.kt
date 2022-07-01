package org.grapheneos.apps.client.ui.mainScreen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.doOnPreDraw
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.onNavDestinationSelected
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.transition.platform.MaterialElevationScale
import com.google.android.material.transition.platform.MaterialFade
import com.google.android.material.transition.platform.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.MainScreenBinding
import org.grapheneos.apps.client.item.MetadataCallBack
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo.Companion.applyFilter
import org.grapheneos.apps.client.utils.runOnUiThread
import org.grapheneos.apps.client.utils.showSnackbar

@AndroidEntryPoint
class MainScreen : Fragment() {

    private lateinit var binding: MainScreenBinding
    private val appsViewModel by lazy {
        requireContext().applicationContext as App
    }
    private val state by viewModels<MainScreenState>()
    private var lastItems: List<InstallablePackageInfo>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = MainScreenBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as MenuHost).addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.main_menu, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    return menuItem.onNavDestinationSelected(findNavController())
                }

            },
            viewLifecycleOwner
        )

        val appsListAdapter = AppsListAdapter(this)
        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
        }

        binding.appsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = appsListAdapter
            itemAnimator = DefaultItemAnimator().apply {
                changeDuration = 0
            }
        }

        state.getFilter().observe(viewLifecycleOwner) { filters ->
            binding.apply {
                grapheneOS.isChecked = filters.contains(state.grapheneOs)
                googleMirror.isChecked = filters.contains(state.googleMirror)
                thirdPartyApps.isChecked = filters.contains(state.buildByGrapheneOs)
            }
            lastItems?.applyFilter(state.getLastFilter())?.let {
                appsListAdapter.submitList(it)
            }
        }

        binding.apply {
            grapheneOS.setOnCheckedChangeListener { _, isChecked ->
                state.modifyFilter(state.grapheneOs, isChecked)
            }
            googleMirror.setOnCheckedChangeListener { _, isChecked ->
                state.modifyFilter(state.googleMirror, isChecked)
            }
            thirdPartyApps.setOnCheckedChangeListener { _, isChecked ->
                state.modifyFilter(state.buildByGrapheneOs, isChecked)
            }
        }

        appsViewModel.packageLiveData.observe(
            viewLifecycleOwner
        ) { newValue ->
            runOnUiThread {
                val packagesInfoMap = newValue ?: return@runOnUiThread
                val sent = InstallablePackageInfo.fromMap(newValue)
                lastItems = sent
                updateUi(isSyncing = false, packagesInfoMap.isEmpty())
                appsListAdapter.submitList(sent.applyFilter(state.getLastFilter()))
            }
        }

        binding.retrySync.setOnClickListener { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        exitTransition = MaterialFadeThrough()
        reenterTransition = MaterialFade()
    }

    fun installPackage(root: View, appName: String, pkgName: String) {
        if (!appsViewModel.areDependenciesInstalled(pkgName)) {
            navigateToDetailsScreen(root, appName, pkgName, true)
        } else {
            appsViewModel.handleOnClick(pkgName) { msg ->
                showSnackbar(msg)
            }
        }
    }

    fun cancelDownload(pkgName: String) = appsViewModel.cancelDownload(pkgName)

    fun navigateToDetailsScreen(
        root: View,
        appName: String,
        pkgName: String,
        installationRequested: Boolean = false
    ) {
        exitTransition = MaterialElevationScale(false)
        reenterTransition = MaterialElevationScale(true)
        val extra = FragmentNavigatorExtras(root to getString(R.string.detailsScreenTransition))
        findNavController().navigate(
            MainScreenDirections.actionToDetailsScreen(
                pkgName,
                appName,
                installationRequested
            ), extra
        )
    }

    private fun refresh() {
        updateUi(isSyncing = true, canRetry = false)
        appsViewModel.refreshMetadata {
            updateUi(isSyncing = false, canRetry = !it.isSuccessFull)
            if (it !is MetadataCallBack.Success) {
                showSnackbar(
                    it.genericMsg + if (it.error != null) "\n${it.error.localizedMessage}" else "",
                    !it.isSuccessFull
                )
            }
        }
    }

    private fun updateUi(isSyncing: Boolean = true, canRetry: Boolean = false) {
        runOnUiThread {
            binding.apply {
                syncing.isVisible = isSyncing
                syncingProgressbar.isVisible = isSyncing
                appsRecyclerView.isGone = isSyncing || canRetry
                retrySync.isVisible = !isSyncing && canRetry
                filter.isVisible = !isSyncing && !canRetry
            }
        }
    }

}
