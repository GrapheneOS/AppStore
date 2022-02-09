package org.grapheneos.apps.client.ui.updateScreen

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.ItemAppsBinding
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.utils.AppSourceHelper

class UpdatesListAdapter(
    private val onUpdateListener: (packageName: String) -> Unit
) :
    ListAdapter<InstallablePackageInfo, UpdatesListAdapter.UpdatesListViewHolder>(
        InstallablePackageInfo.UiItemDiff()
    ) {

    inner class UpdatesListViewHolder(
        private val binding: ItemAppsBinding,
        private val onUpdateListener: (packageName: String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val item = currentList[position]
            binding.appName.text = item.packageInfo.selectedVariant.appName
            binding.quickAction.text = App.getString(R.string.update)
            binding.quickAction.setOnClickListener {
                onUpdateListener.invoke(item.name)
            }
            binding.publisher.text = AppSourceHelper.getCategoryName(item.name)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = UpdatesListViewHolder(
        ItemAppsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        ),
        onUpdateListener
    )

    override fun onBindViewHolder(holder: UpdatesListViewHolder, position: Int) =
        holder.bind(position)

}