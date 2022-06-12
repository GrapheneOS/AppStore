package org.grapheneos.apps.client.ui.detailsScreen

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.grapheneos.apps.client.databinding.ItemDependencyBinding
import org.grapheneos.apps.client.item.DownloadStatus
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.utils.AppSourceHelper
import kotlin.math.roundToInt

class DependencyAdapter :
    ListAdapter<InstallablePackageInfo, DependencyAdapter.DependencyViewHolder>(
        InstallablePackageInfo.UiItemDiff(isDownloadUi = true)
    ) {

    companion object {
        fun DownloadStatus.Downloading.toSizeInfo(includePercent: Boolean = true) =
            if (includePercent) "${downloadedSize.toMB()} /" +
                    " ${downloadSize.toMB()} MB,  " +
                    "${downloadedPercent.roundToInt()} %"
            else "${downloadedSize.toMB()} /" +
                    " ${downloadSize.toMB()} MB  "

        private fun Int.toMB(): String = String.format("%.2f", (this / 1024.0 / 1024.0))

    }

    inner class DependencyViewHolder(private val binding: ItemDependencyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val info = currentList[position].packageInfo
            binding.apply {
                appName.text = info.selectedVariant.appName
                appName.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    marginEnd = pxToDp(8)
                }
                publisher.text = AppSourceHelper.getCategoryName(info.pkgName)
                progressBar.isVisible = info.downloadStatus != null
                downloadSize.isVisible = info.downloadStatus != null
            }
            val progress = info.downloadStatus
            if (progress is DownloadStatus.Downloading) {
                binding.apply {
                    progressBar.setProgress(progress.downloadedPercent.toInt(), true)
                    downloadSize.text = progress.toSizeInfo()
                }

            }
        }
    }

    fun pxToDp(px: Int) = (px / Resources.getSystem().displayMetrics.density).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = DependencyViewHolder(
        ItemDependencyBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
    )

    override fun onBindViewHolder(holder: DependencyViewHolder, position: Int) =
        holder.bind(position)
}