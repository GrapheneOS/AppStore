package app.grapheneos.apps.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MenuRes
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.onNavDestinationSelected
import androidx.viewbinding.ViewBinding
import com.google.android.material.transition.platform.MaterialSharedAxis

abstract class ViewBindingFragment<T : ViewBinding> : Fragment(), MenuProvider {

    abstract fun inflate(inflater: LayoutInflater, container: ViewGroup?, attach: Boolean): T

    private var _views: T? = null

    fun views() = _views!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (menuXml != 0) {
            requireActivity().addMenuProvider(this, this, Lifecycle.State.STARTED)
        }
    }

    override final fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedState: Bundle?): View {
        val views = inflate(inflater, container, false)
        this._views = views
        return views.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val views = views()
        check(view === views.root)
        onViewsCreated(views, savedInstanceState)
        setupSlideTransitions(this)
    }

    abstract fun onViewsCreated(views: T, savedInstanceState: Bundle?)

    override fun onDestroyView() {
        super.onDestroyView()
        _views = null
    }

    @MenuRes
    open val menuXml = 0

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(menuXml, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return menuItem.onNavDestinationSelected(findNavController())
    }
}

fun setupSlideTransitions(fragment: Fragment) {
    fragment.apply {
        val axis = MaterialSharedAxis.Y
//        val axis = MaterialSharedAxis.X
        enterTransition = MaterialSharedAxis(axis, true)
        exitTransition = MaterialSharedAxis(axis, true)
        reenterTransition = MaterialSharedAxis(axis, false)
        returnTransition = MaterialSharedAxis(axis, false)
    }

    fragment.postponeEnterTransition()
    fragment.requireView().doOnPreDraw { fragment.startPostponedEnterTransition() }
}
