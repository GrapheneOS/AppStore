package app.grapheneos.apps.ui

import android.os.Bundle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import app.grapheneos.apps.core.PackageState
import app.grapheneos.apps.PackageStates

abstract class PackageListFragment<T : ViewBinding> : ViewBindingFragment<T>(), PackageStates.StateListener {
    protected lateinit var packages: Map<String, PackageState>
        private set
    protected lateinit var listAdapter: PackageListAdapter
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PackageStates.addListener(this, this)
    }

    override fun onPackageStateChanged(state: PackageState) {
        listAdapter.updateItem(state)
    }

    override fun onAllPackageStatesChanged(states: Map<String, PackageState>) {
        this@PackageListFragment.packages = states
        updateList()
    }

    override fun onNumberOfOutdatedPackagesChanged(value: Int) {
        if (this@PackageListFragment is UpdatesScreen) {
            updateList()
        }
    }

    protected fun createListAdapter(listView: RecyclerView) {
        listAdapter = PackageListAdapter(this).also {
            it.setupRecyclerView(listView)
        }
    }

    abstract fun updateList()
}
