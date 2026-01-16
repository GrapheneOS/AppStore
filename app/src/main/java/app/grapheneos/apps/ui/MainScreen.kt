package app.grapheneos.apps.ui

import android.content.Intent
import android.os.Bundle
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import app.grapheneos.apps.BuildConfig
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.core.RepoUpdateError
import app.grapheneos.apps.core.mainHandler
import app.grapheneos.apps.databinding.MainScreenBinding
import app.grapheneos.apps.util.intent
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

open class MainScreen : PackageListFragment<MainScreenBinding>(), MenuProvider {
    override val menuXml = R.menu.main_screen_menu

    override fun inflate(inflater: LayoutInflater, container: ViewGroup?, attach: Boolean) =
        MainScreenBinding.inflate(inflater, container, attach)

    override fun onViewsCreated(views: MainScreenBinding, savedInstanceState: Bundle?) {
        createListAdapter(views.appList)

        views.swipeRefreshContainer.apply {
            setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
            setColorSchemeColors(
                MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
        }

        views.swipeRefreshContainer.setOnRefreshListener {
            CoroutineScope(Dispatchers.Main).launch {
                PackageStates.requestRepoUpdate(force = true, isManuallyRequested = true)
            }
        }

        badgeShowingNumberOfOutdatedPackages = BadgeDrawable.create(requireActivity())

        // Block for a bit if repo metadata is still being obtained to avoid showing refresh indicator
        // that would almost always would be hidden very soon (10s to low 100s of milliseconds).
        // Repo metadata is cached, so this will happen only if it was never successfully obtained
        // (ie first launch that happened before auto update job got a chance to run)
        if (PackageStates.repo.isDummy) {
            val job = PackageStates.repoUpdateJob
            if (job != null) {
                mainHandler.post {
                    runBlocking {
                        try {
                            suppressRepoUpdateResultCallback = true
                            withTimeout(500) {
                                val error = job.await()
                                onRepoUpdateResult(error)
                            }
                        } catch (e: TimeoutCancellationException) {
                            views.swipeRefreshContainer.isRefreshing = true
                        } finally {
                            suppressRepoUpdateResultCallback = false
                        }
                    }
                }
            }
        }
    }

    private var suppressRepoUpdateResultCallback = false
    private var shownRepoErrorUpdateDialog = false

    override fun onRepoUpdateResult(error: RepoUpdateError?) {
        if (suppressRepoUpdateResultCallback) {
            return
        }
        val views = views()
        val isDummyRepo = PackageStates.repo.isDummy
        val showPlaceholder = isDummyRepo && error != null
        views.placeholderText.isVisible = showPlaceholder
        views.appList.isInvisible = showPlaceholder
        views.swipeRefreshContainer.isRefreshing = false

        if (error == null) {
            return
        }

        if ((isDummyRepo && !shownRepoErrorUpdateDialog) || error.isNotable()) {
            shownRepoErrorUpdateDialog = true
            ErrorDialog.show(this, error)
        } else if (error.wasUpdateManuallyRequested) {
            Snackbar.make(views.root, R.string.unable_to_fetch_app_list, Snackbar.LENGTH_SHORT).run {
                setAction(R.string.snackbar_action_details) { _ ->
                    ErrorDialog.show(this@MainScreen, error)
                }
                show()
            }
        }
    }

    override fun updateList() {
        val list = packages.values.filter {
            if (this::class == MainScreen::class) {
                it.rPackage.common.isTopLevel
            } else {
                true
            }
        }.sortedWith { a, b ->
            val pkg1 = a.rPackage
            val pkg2 = b.rPackage
            var res = pkg1.source.compareTo(pkg2.source)
            if (res == 0) {
                res = pkg1.label.compareTo(pkg2.label)
            }
            res
        }

        listAdapter.updateList(list)
    }

    override fun onNumberOfOutdatedPackagesChanged(value: Int) {
        requireActivity().invalidateMenu()
    }

    private lateinit var badgeShowingNumberOfOutdatedPackages: BadgeDrawable

    @com.google.android.material.badge.ExperimentalBadgeUtils
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        super.onCreateMenu(menu, menuInflater)

        PackageStates.numberOfOutdatedPackages.let {
            val showMenuItemAndBadge = it > 0

            if (showMenuItemAndBadge) {
                val activity = requireActivity() as MainActivity
                badgeShowingNumberOfOutdatedPackages.number = it
                BadgeUtils.attachBadgeDrawable(
                    badgeShowingNumberOfOutdatedPackages,
                    activity.views.toolbar,
                    R.id.updates_screen
                )
            } else {
                menu.removeItem(R.id.updates_screen)
            }
        }

        if (BuildConfig.DEBUG) {
            newWindowMenuItem = menu.add("New window")
        }
    }

    private lateinit var newWindowMenuItem: MenuItem

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (BuildConfig.DEBUG) {
            if (newWindowMenuItem === menuItem) {
                val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                startActivity(intent<MainActivity>().addFlags(flags))
                return true
            }
        }
        return super.onMenuItemSelected(menuItem)
    }
}
