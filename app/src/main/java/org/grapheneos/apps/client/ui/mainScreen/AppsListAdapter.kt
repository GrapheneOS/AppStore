package org.grapheneos.apps.client.ui.mainScreen

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.ItemAppsBinding
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.utils.AppSourceHelper

class AppsListAdapter(private val mainScreen: MainScreen) :
    ListAdapter<InstallablePackageInfo, AppsListAdapter.AppsListViewHolder>(
        InstallablePackageInfo.UiItemDiff()
    ) {

    inner class AppsListViewHolder(private val binding: ItemAppsBinding) :
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
                    if (isDownloading) {
                        mainScreen.cancelDownload(packageName)
                    } else {
                        mainScreen.installPackage(
                            root,
                            packageVariant.appName,
                            packageName
                        )
                    }
                }

                quickAction.text =
                    if (isDownloading) App.getString(R.string.cancel) else installStatus.status
                publisher.text = AppSourceHelper.getCategoryName(packageName)
                releaseTag.isVisible = "stable" != packageVariant.type
                releaseTag.text = packageVariant.type
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = AppsListViewHolder(
        ItemAppsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
    )

    override fun onBindViewHolder(holder: AppsListViewHolder, position: Int) = holder.bind(position)

    override fun getItemCount() = currentList.size

}
