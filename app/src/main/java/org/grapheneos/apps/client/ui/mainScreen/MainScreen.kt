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
import org.grapheneos.apps.client.item.InstallStatus
import org.grapheneos.apps.client.item.MetadataCallBack
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.utils.showSnackbar

@AndroidEntryPoint
class MainScreen : Fragment() {

    private lateinit var binding: MainScreenBinding
    private val appsViewModel by lazy {
        requireContext().applicationContext as App
    }

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
            R.id.update_all_menu -> {
                appsViewModel.updateAllUpdatableApps { msg -> showSnackbar(msg) }
                true
            }
            else -> item.onNavDestinationSelected(findNavController()) ||
                    super.onOptionsItemSelected(item)
        }
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

        appsViewModel.packageLiveData.observe(
            viewLifecycleOwner
        ) { newValue ->
            runOnUiThread {
                val packagesInfoMap = newValue ?: return@runOnUiThread
                val sent = InstallablePackageInfo.fromMap(newValue)
                updateUi(isSyncing = false, packagesInfoMap.isNullOrEmpty())
                appsListAdapter.submitList(sent.sortedByDescending {
                    it.packageInfo.installStatus is InstallStatus.Installable
                })
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
        if (!appsViewModel.isDependenciesInstalled(pkgName)) {
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

    private fun runOnUiThread(action: Runnable) {
        activity?.runOnUiThread(action)
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
            }
        }
    }

}
