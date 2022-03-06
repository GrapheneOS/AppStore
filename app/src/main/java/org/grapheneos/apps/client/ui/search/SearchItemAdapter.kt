package org.grapheneos.apps.client.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.grapheneos.apps.client.databinding.ItemSearchBinding
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.utils.AppSourceHelper

class SearchItemListAdapter(private val mainScreen: SearchScreen) :
    ListAdapter<InstallablePackageInfo, SearchItemListAdapter.SearchItemViewHolder>(
        InstallablePackageInfo.UiItemDiff(isDownloadUi = false)
    ) {

    inner class SearchItemViewHolder(private val binding: ItemSearchBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val currentItem = currentList[position]
            val info = currentItem.packageInfo
            val packageVariant = info.selectedVariant
            val installStatus = info.installStatus
            val downloadStatus = info.downloadStatus
            val packageName = packageVariant.pkgName
            val isDownloading = downloadStatus != null && downloadStatus.isDownloading

            binding.apply {
                root.transitionName = packageName
                appName.text = packageVariant.appName
                root.setOnClickListener {
                    mainScreen.navigateToDetailsScreen(
                        root,
                        packageVariant.appName,
                        packageName
                    )
                }
                quickAction.setOnClickListener {
                    mainScreen.installPackage(
                        root,
                        packageVariant.appName,
                        packageName
                    )
                }
                quickAction.isEnabled = !isDownloading
                quickAction.text = installStatus.status
                publisher.text = AppSourceHelper.getCategoryName(packageName)
                releaseTag.isVisible = "stable" != packageVariant.type
                releaseTag.text = packageVariant.type
            }
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        SearchItemViewHolder(
            ItemSearchBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )

    override fun onBindViewHolder(holder: SearchItemViewHolder, position: Int) =
        holder.bind(position)
}