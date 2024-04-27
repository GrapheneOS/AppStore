package app.grapheneos.apps.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.viewbinding.ViewBinding
import app.grapheneos.apps.NavGraphDirections
import app.grapheneos.apps.R
import app.grapheneos.apps.core.PackageState
import app.grapheneos.apps.core.ReleaseChannel
import app.grapheneos.apps.databinding.PackageListItemBinding
import app.grapheneos.apps.util.maybeSetText
import coil.load
import coil.transform.RoundedCornersTransformation

class ViewBindingVH<T : ViewBinding>(val binding: T) : ViewHolder(binding.root)

class PackageListAdapter(val fragment: Fragment) : RecyclerView.Adapter<ViewBindingVH<PackageListItemBinding>>() {
    init {
        setHasStableIds(true)
    }
    var list = emptyList<PackageState>(); private set
    @SuppressLint("NotifyDataSetChanged")
    fun updateList(v: List<PackageState>) {
        list = v
        notifyDataSetChanged()
    }

    fun updateItem(v: PackageState) {
        val idx = list.indexOfFirst { it === v }
        if (idx >= 0) {
            notifyItemChanged(idx)
        }
    }

    override fun onBindViewHolder(holder: ViewBindingVH<PackageListItemBinding>, position: Int) {
        val item = list[position]

        holder.binding.set(fragment, item)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PackageListItemBinding.inflate(fragment.layoutInflater, parent,false).let { binding ->
            binding.setOnClickListener(fragment)
            ViewBindingVH(binding)
        }

    override fun getItemId(position: Int) = list[position].id

    override fun getItemCount() = list.size

    fun setupRecyclerView(rv: RecyclerView, setupWindowInsetsListener: Boolean = true) {
        rv.layoutManager = LinearLayoutManager(rv.context)
        rv.adapter = this
        // item onClick becomes unreliable when item is updating if itemAnimator is set
        rv.itemAnimator = null

        if (setupWindowInsetsListener) {
            ViewCompat.setOnApplyWindowInsetsListener(rv) { v, insets ->
                val paddingInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updatePadding(bottom = paddingInsets.bottom)
                insets
            }
        }
    }
}

fun PackageListItemBinding.set(fragment: Fragment, pkgState: PackageState) {
    val rPackage = pkgState.rPackage

    val iconUrl = rPackage.common.iconUrl
    if (iconUrl != null && pkgIcon.tag != iconUrl) {
        pkgIcon.load(iconUrl) {
            transformations(RoundedCornersTransformation(20f))
            placeholder(R.drawable.ic_placeholder_app_icon)
        }
    }

    if (iconUrl == null) {
        pkgIcon.setImageResource(R.drawable.ic_placeholder_app_icon)
    }

    pkgIcon.tag = iconUrl

    this.pkgName.maybeSetText(rPackage.label)

    publisher.maybeSetText(rPackage.source.uiName)

    val releaseChannel = pkgState.preferredReleaseChannel()
    val isStable = releaseChannel == ReleaseChannel.stable
    releaseTag.isGone = isStable
    if (!isStable) {
        releaseTag.maybeSetText(releaseChannel.uiName)
    }

    if (fragment is UpdatesScreen && pkgState.status() == PackageState.Status.OUT_OF_DATE) {
        status.maybeSetText(pkgState.getDownloadSizeUiString())
    } else {
        status.maybeSetText(pkgState.statusString(status.context))
    }

    root.tag = pkgState
}

fun PackageListItemBinding.setOnClickListener(fragment: Fragment) {
    root.setOnClickListener { root ->
        val packageState = root.tag as PackageState
        val pkgName = packageState.pkgName
        fragment.findNavController().navigate(NavGraphDirections.actionToDetailsScreen(pkgName))
    }
}
