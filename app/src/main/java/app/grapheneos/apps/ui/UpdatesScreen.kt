package app.grapheneos.apps.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.core.InstallParams
import app.grapheneos.apps.core.PackageState
import app.grapheneos.apps.core.collectOutdatedPackageGroups
import app.grapheneos.apps.core.startPackageUpdate
import app.grapheneos.apps.databinding.UpdatesScreenBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class UpdatesScreen : PackageListFragment<UpdatesScreenBinding>(), MenuProvider {
    class UpdatesScreenVM : ViewModel() {
        var updateAllInProgress = false
        var cancelableJobs: List<Job>? = null
    }

    private val model by viewModels<UpdatesScreenVM>()

    override fun inflate(inflater: LayoutInflater, container: ViewGroup?, attach: Boolean) =
        UpdatesScreenBinding.inflate(inflater, container, attach)

    override fun onViewsCreated(views: UpdatesScreenBinding, savedInstanceState: Bundle?) {
        createListAdapter(views.updatesList)

        views.updateOrCancelAll.setOnClickListener {
            model.cancelableJobs?.let {
                it.forEach { it.cancel() }
                model.cancelableJobs = null
                model.updateAllInProgress = false
                updateList()
                return@setOnClickListener
            }

            if (model.updateAllInProgress) {
                return@setOnClickListener
            }

            model.updateAllInProgress = true
            updateList()

            CoroutineScope(Dispatchers.Main).launch {
                val outdatedPackageGroups = collectOutdatedPackageGroups()
                if (outdatedPackageGroups.isNotEmpty()) {
                    val installParams = InstallParams(network = null, isUserInitiated = true, isUpdate = true)
                    val jobs = startPackageUpdate(installParams, outdatedPackageGroups)
                    model.cancelableJobs = jobs
                    PackageStates.dispatchAllStatesChanged()
                    try {
                        val pkgInstallerResults = jobs.awaitAll()
                        model.cancelableJobs = null
                        PackageStates.dispatchAllStatesChanged()
                        pkgInstallerResults.awaitAll()
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
                model.cancelableJobs = null
                model.updateAllInProgress = false
                PackageStates.dispatchAllStatesChanged()
            }
        }
    }

    override fun onPackageStateChanged(state: PackageState) {
        super.onPackageStateChanged(state)
        val status = state.status()
        if (status != PackageState.Status.OUT_OF_DATE && status != PackageState.Status.INSTALLING) {
            updateList()
        } else {
            updateAllViewsExceptList()
        }
    }

    override fun updateList() {
        packages.values.filter {
            it.isOutdated()
        }.sortedBy {
            it.rPackage.label
        }.let { list ->
            listAdapter.updateList(list)
        }
        updateAllViewsExceptList()
    }

    private fun updateAllViewsExceptList() {
        val list = listAdapter.list
        val isListNotEmpty = list.isNotEmpty()

        views().apply {
            placeholderText.isGone = isListNotEmpty
            updatesList.isVisible = isListNotEmpty

            updateCount.apply {
                isVisible = isListNotEmpty
                text = resources.getQuantityString(R.plurals.number_of_updates, list.size, list.size)
            }

            updateOrCancelAll.apply {
                val visible = list.isNotEmpty()
                isVisible = visible
                if (visible) {
                    isEnabled = (!model.updateAllInProgress && list.any { !it.isInstalling() }) ||
                            model.cancelableJobs != null

                    val btnText = if (model.updateAllInProgress)
                        R.plurals.cancel_plural else R.plurals.start_update_button

                    setText(resources.getQuantityText(btnText, list.size))
                }
            }

        }
    }
}
