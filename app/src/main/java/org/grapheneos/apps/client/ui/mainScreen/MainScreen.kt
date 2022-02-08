package org.grapheneos.apps.client.ui.mainScreen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.onNavDestinationSelected
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.MainScreenBinding
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo

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
        val appsListAdapter = AppsListAdapter(onInstallItemClick = { packageName ->
            appsViewModel.handleOnClick(packageName) { msg ->
                showSnackbar(msg)
            }
        }, onChannelItemClick = { packageName, channel, callback ->
            appsViewModel.handleOnVariantChange(packageName, channel, callback)
        }, onUninstallItemClick = { packageName ->
            appsViewModel.uninstallPackage(packageName) { msg ->
                showSnackbar(msg)
            }
        }, onAppInfoItemClick = { packageName ->
            appsViewModel.openAppDetails(packageName)
        })

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
                val sent = packagesInfoMap.toInstall()
                updateUi(isSyncing = false, packagesInfoMap.isNullOrEmpty())
                appsListAdapter.submitList(sent)
            }
        }

        binding.retrySync.setOnClickListener { refresh() }
        refresh()
    }

    private fun Map<String, PackageInfo>.toInstall(): List<InstallablePackageInfo> {
        val result = mutableListOf<InstallablePackageInfo>()
        val value = this.values
        for (item in value) {
            result.add(InstallablePackageInfo(item.id, item))
        }
        return result
    }

    private fun runOnUiThread(action: Runnable) {
        activity?.runOnUiThread(action)
    }

    private fun refresh() {
        updateUi(isSyncing = true, canRetry = false)
        appsViewModel.refreshMetadata {
            updateUi(isSyncing = false, canRetry = !it.isSuccessFull)
            showSnackbar(
                it.genericMsg + if (it.error != null) "\n${it.error.localizedMessage}" else "",
                !it.isSuccessFull
            )
        }
    }

    private fun updateUi(isSyncing: Boolean = true, canRetry: Boolean = false) {
        runOnUiThread {
            binding.syncing.isVisible = isSyncing
            binding.appsRecyclerView.isGone = isSyncing || canRetry
            binding.retrySync.isVisible = !isSyncing && canRetry
        }
    }

    private fun showSnackbar(msg: String, isError: Boolean? = null) {
        val snackbar = Snackbar.make(
            DynamicColors.wrapContextIfAvailable(requireContext()),
            binding.root,
            msg,
            Snackbar.LENGTH_SHORT
        )

        if (isError == true) {
            snackbar.setTextColor(requireActivity().getColor(android.R.color.holo_red_light))
        }
        snackbar.show()
    }

}
