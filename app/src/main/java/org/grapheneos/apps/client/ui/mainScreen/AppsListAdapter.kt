package org.grapheneos.apps.client.ui.mainScreen

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.ItemAppsBinding
import org.grapheneos.apps.client.item.DownloadStatus
import org.grapheneos.apps.client.item.InstallStatus
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.item.PackageVariant
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import kotlin.math.roundToInt

class AppsListAdapter(
    private val onInstallItemClick: (packageName: String) -> Unit,
    private val onChannelItemClick: (
        packageName: String, channel: String,
        (packageInfo: PackageInfo) -> Unit
    ) -> Unit,
    private val onUninstallItemClick: (packageName: String) -> Unit,
    private val onAppInfoItemClick: (packageName: String) -> Unit,
) :
    ListAdapter<InstallablePackageInfo, AppsListAdapter.AppsListViewHolder>(
        InstallablePackageInfo.UiItemDiff()
    ) {

    inner class AppsListViewHolder(
        private val binding: ItemAppsBinding,
        private val onInstallItemClick: (packageName: String) -> Unit,
        private val onChannelItemClick: (
            packageName: String, channel: String,
            (packageInfo: PackageInfo) -> Unit
        ) -> Unit,
        private val onUninstallItemClick: (packageName: String) -> Unit,
        private val onAppInfoItemClick: (packageName: String) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val currentItem = currentList[position]
            val info = currentItem.packageInfo
            val installStatus = info.installStatus
            val downloadStatus = info.downloadStatus
            val packageVariant = info.selectedVariant
            val packageVariants = info.allVariant
            val packageName = packageVariant.pkgName

            rebind(position)

            if (downloadStatus != null) {
                binding.apply {
                    install.text = downloadStatus.status
                    install.isEnabled = !downloadStatus.isDownloading
                    downloadSizeInfo.isGone = !downloadStatus.isDownloading
                    downloadProgress.isInvisible = !downloadStatus.isDownloading
                    appInfoGroup.isInvisible = true
                    channel.isEnabled = false

                    if (downloadStatus is DownloadStatus.Downloading) {
                        val progress = downloadStatus.downloadedPercent.roundToInt()
                        val sizeInfo = "${downloadStatus.downloadedSize.toMB()} MB out of " +
                                "${downloadStatus.downloadSize.toMB()} MB," +
                                "  $progress %"
                        downloadProgress.isIndeterminate =
                            (downloadStatus.downloadSize == 0) && (downloadStatus.downloadedSize == 0)
                        downloadProgress.max = 100
                        downloadProgress.setProgressCompat(
                            downloadStatus.downloadedPercent.roundToInt(),
                            true
                        )
                        downloadSizeInfo.text = sizeInfo
                    }
                }
            } else {
                binding.apply {
                    install.text = installStatus.status
                    if (installStatus is InstallStatus.Installing || installStatus is InstallStatus.Uninstalling
                        || installStatus is InstallStatus.Pending) {
                        downloadProgress.isInvisible = false
                        downloadProgress.isIndeterminate = true
                        downloadSizeInfo.isGone = true
                        install.isEnabled = false
                        channel.isEnabled = false
                    } else {
                        install.isEnabled = true
                        downloadProgress.isInvisible = true
                        downloadSizeInfo.isGone = true
                        channel.isEnabled = true
                    }
                }
            }

            binding.install.setOnClickListener {
                binding.installGroup.uncheck(binding.install.id)
                onInstallItemClick.invoke(packageName)
            }

            binding.channel.setOnClickListener { view ->
                binding.installGroup.uncheck(binding.channel.id)
                val listPopupWindowButton = binding.channel
                val listPopupWindow = ListPopupWindow(
                    view.context, null,
                    R.attr.listPopupWindowStyle
                )
                listPopupWindow.anchorView = listPopupWindowButton
                val mutableItems = mutableListOf<String>()
                packageVariants.forEach { variant: PackageVariant ->
                    mutableItems.add(variant.type)
                }
                val items = mutableItems.toList()
                val adapter = ArrayAdapter(view.context, R.layout.list_popup_window_item, items)
                listPopupWindow.setAdapter(adapter)
                listPopupWindow.setOnItemClickListener { _, _, whichVariant: Int, _ ->
                    val chosenVariant = items[whichVariant]
                    ChannelPreferenceManager.savePackageChannel(
                        view.context,
                        packageName,
                        chosenVariant
                    )
                    onChannelItemClick.invoke(packageName, chosenVariant) { packageInfo ->
                        rebind(position, packageInfo)
                    }
                    listPopupWindow.dismiss()
                }
                listPopupWindow.show()
            }

            binding.appInfo.setOnClickListener {
                binding.appInfoGroup.uncheck(binding.appInfo.id)
                onAppInfoItemClick.invoke(packageName)
            }

            binding.appRemove.setOnClickListener {
                binding.appInfoGroup.uncheck(binding.appRemove.id)
                onUninstallItemClick.invoke(packageName)
            }

        }

        private fun rebind(
            position: Int,
            updatedPackageInfo: PackageInfo = currentList[position].packageInfo
        ) {
            val installStatus = updatedPackageInfo.installStatus
            val packageVariant = updatedPackageInfo.selectedVariant

            binding.apply {
                appName.text = updatedPackageInfo.selectedVariant.appName
                latestVersion.text = installStatus.latestV
                installedVersion.text = installStatus.installedV
                channel.text = packageVariant.type
                install.text = installStatus.status
                appInfoGroup.isInvisible = installStatus.installedV == "N/A"
                if (installedVersion.text == "N/A"
                    && install.text in listOf("install", "failed")
                ) {
                    appInfoGroup.isGone = true
                }
                if (packageVariant.type != ChannelPreferenceManager
                        .getPackageChannel(binding.root.context, packageVariant.pkgName)
                ) {
                    ChannelPreferenceManager.savePackageChannel(
                        binding.root.context,
                        packageVariant.pkgName, packageVariant.type
                    )
                }
            }
        }
    }

    private fun Int.toMB(): String = String.format("%.3f", (this / 1024.0 / 1024.0))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = AppsListViewHolder(
        ItemAppsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        ),
        onInstallItemClick,
        onChannelItemClick,
        onUninstallItemClick,
        onAppInfoItemClick,
    )

    override fun onBindViewHolder(holder: AppsListViewHolder, position: Int) = holder.bind(position)

    override fun getItemCount() = currentList.size

}
