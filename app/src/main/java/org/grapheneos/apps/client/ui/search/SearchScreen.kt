package org.grapheneos.apps.client.ui.search

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
import androidx.core.view.doOnPreDraw
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
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
import org.grapheneos.apps.client.databinding.SearchScreenBinding
import org.grapheneos.apps.client.ui.mainScreen.MainScreenDirections
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo.Companion.applySearchQueryFilter
import org.grapheneos.apps.client.utils.runOnUiThread
import org.grapheneos.apps.client.utils.showSnackbar
import javax.inject.Inject

@AndroidEntryPoint
class SearchScreen : Fragment() {

    @Inject
    lateinit var searchState: SearchScreenState
    private lateinit var binding: SearchScreenBinding
    private val appDataModel by lazy {
        requireContext().applicationContext as App
    }
    private val searchScreenAdapter by lazy {
        SearchItemListAdapter(this)
    }
    private var lastItems: List<InstallablePackageInfo> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SearchScreenBinding.inflate(layoutInflater, container, false)

        exitTransition = MaterialFadeThrough()
        reenterTransition = MaterialFade()
        enterTransition = MaterialFade()

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, inset ->

            val paddingInsets = inset.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = paddingInsets.bottom
            }
            inset
        }

        (requireActivity() as MenuHost).addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.search_screen_menu, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    return menuItem.onNavDestinationSelected(findNavController())
                }
            },
            viewLifecycleOwner
        )

        binding.appsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = searchScreenAdapter
            itemAnimator = DefaultItemAnimator().apply {
                changeDuration = 0
            }
        }

        appDataModel.packageLiveData.observe(viewLifecycleOwner) { newValue ->
            runOnUiThread {
                val sent = InstallablePackageInfo.fromMap(newValue)
                lastItems = sent
                submit()
            }
        }

        searchState.searchQuery.observe(viewLifecycleOwner) {
            submit()
        }

    }

    private fun submit() {
        val allItems = mutableListOf<InstallablePackageInfo>().apply {
            addAll(lastItems)
        }
        val query = searchState.getCurrentQuery()
        val filterList = allItems.applySearchQueryFilter(query)

        if (allItems.isEmpty()) {
            //sync isn't finished yet or something?
            submitAndHideUi()
        } else if (query.isBlank() || query.isEmpty()) {
            //search query is empty
            submitAndHideUi()
        } else if (filterList.isEmpty()) {
            //no match found LOL
            submitAndHideUi(false)
        } else {
            submitAndHideUi(true, filterList)
        }

    }

    private fun submitAndHideUi(
        hide: Boolean = true,
        list: List<InstallablePackageInfo> = emptyList()
    ) {
        binding.output.isGone = hide
        binding.appsRecyclerView.isVisible = hide
        searchScreenAdapter.submitList(list)
    }

    fun installPackage(root: View, appName: String, pkgName: String) {
        if (!appDataModel.areDependenciesInstalled(pkgName)) {
            navigateToDetailsScreen(root, appName, pkgName, true)
        } else {
            appDataModel.handleOnClick(pkgName) { msg ->
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

}
