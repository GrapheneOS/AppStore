package org.grapheneos.apps.client.ui.mainScreen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh -> {
                refresh(true)
                true
            }
            else -> item.onNavDestinationSelected(findNavController()) ||
                super.onOptionsItemSelected(item)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.refresh).isVisible = !binding.swipeRefresh.isRefreshing
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setHasOptionsMenu(true)
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
            swipeRefresh.setOnRefreshListener {
                if (appsViewModel.isDownloading) {
                    binding.swipeRefresh.isRefreshing = false
                    showSnackbar("${App.getString(R.string.sfSecurityError)}"
                        + "\n${App.getString(R.string.refreshInProgress)}", true)
                    return@setOnRefreshListener
                }
                refresh(true)
            }
        }

        appsViewModel.packageLiveData.observe(
            viewLifecycleOwner
        ) { newValue ->
            runOnUiThread {
                val packagesInfoMap = newValue ?: return@runOnUiThread
                val sent = InstallablePackageInfo.fromMap(newValue)
                lastItems = sent
                updateUi(isSyncingResultSuccess = !packagesInfoMap.isEmpty())
                appsListAdapter.submitList(sent.applyFilter(state.getLastFilter()))
            }
        }

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

    private fun refresh(isForcedUpdate: Boolean = false) {
        updateUi()
        appsViewModel.refreshMetadata(isForcedUpdate) {
            updateUi(isSyncingResultSuccess = it.isSuccessFull)
            if (it !is MetadataCallBack.Success) {
                showSnackbar(
                    it.genericMsg + if (it.error != null) "\n${it.error.localizedMessage}" else "",
                    !it.isSuccessFull
                )
            }
        }
    }

    private fun updateUi(isSyncingResultSuccess: Boolean? = null) {
        requireActivity().invalidateOptionsMenu()
        val isFirstScreen = lastItems.isNullOrEmpty()
        val isSyncing = isSyncingResultSuccess == null
        val isSuccess = isSyncingResultSuccess ?: false
        runOnUiThread {
            binding.apply {
                swipeRefresh.isRefreshing = isSyncing
                swipeRefresh.isGone = isFirstScreen && isSyncing
                if (!isFirstScreen && !isSuccess) return@runOnUiThread
                syncing.isVisible = isSyncing
                syncingProgressbar.isVisible = isSyncing
                syncingFailed.isGone = isSuccess || isSyncing
                appsRecyclerView.isGone = !isSuccess
                filter.isVisible = isSuccess
            }
        }
    }

}
