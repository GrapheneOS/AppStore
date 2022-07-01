package org.grapheneos.apps.client.ui.updateScreen

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
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.onNavDestinationSelected
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.transition.platform.MaterialFade
import com.google.android.material.transition.platform.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.UpdatesScreenBinding
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.utils.showSnackbar

@AndroidEntryPoint
class UpdatesScreen : Fragment() {

    private lateinit var binding: UpdatesScreenBinding
    private val appsViewModel by lazy {
        requireContext().applicationContext as App
    }
    private val updatableAdapter = UpdatesListAdapter {
        appsViewModel.handleOnClick(it) {}
    }
    private val observer by lazy {
        { newValue: Map<String, PackageInfo> ->
            val items = InstallablePackageInfo.updatableFromMap(newValue)
            binding.nothingToUpdateUI.isGone = items.isNotEmpty()
            binding.updatesRecyclerView.isVisible = items.isNotEmpty()
            binding.updateCounter.isVisible = items.isNotEmpty()
            binding.updateAll.isVisible = items.isNotEmpty()
            binding.updateAll.isEnabled = items.isNotEmpty()
            updatableAdapter.submitList(items)
        }
    }

    private val updateCounter by lazy {
        appsViewModel.updateCount
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

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

        binding = UpdatesScreenBinding.inflate(layoutInflater, container, false)

        exitTransition = MaterialFadeThrough()
        reenterTransition = MaterialFade()
        enterTransition = MaterialFade()

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.updatesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = updatableAdapter
            itemAnimator = DefaultItemAnimator().apply {
                changeDuration = 0
            }
        }

        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
        }

        appsViewModel.packageLiveData.observe(viewLifecycleOwner, observer)
        updateCounter.observe(viewLifecycleOwner) { count ->
            binding.updateCounter.text = String.format(getString(R.string.xUpdates), count)
        }
        binding.updateAll.setOnClickListener {
            binding.updateAll.isEnabled = false
            appsViewModel.updateAllUpdatableApps { msg -> showSnackbar(msg) }
        }

    }
}