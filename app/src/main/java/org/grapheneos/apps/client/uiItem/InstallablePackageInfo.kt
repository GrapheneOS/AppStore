package org.grapheneos.apps.client.uiItem

import androidx.recyclerview.widget.DiffUtil
import org.grapheneos.apps.client.item.DownloadStatus
import org.grapheneos.apps.client.item.InstallStatus
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.utils.AppSourceHelper

data class InstallablePackageInfo(
    val name: String,
    val packageInfo: PackageInfo
) {

    companion object {
        fun fromMap(list: Map<String, PackageInfo>): List<InstallablePackageInfo> {
            val result = mutableListOf<InstallablePackageInfo>()
            val value = list.values
            for (item in value) {
                result.add(InstallablePackageInfo(item.id, item))
            }
            return result
        }

        fun List<InstallablePackageInfo>.applyFilter(
            filters: List<AppSourceHelper.BuildType>
        ): List<InstallablePackageInfo> {

            if (filters.isEmpty()) return this

            val items = this
            val result = mutableListOf<InstallablePackageInfo>()
            items.forEach { item ->
                if (filters.contains(AppSourceHelper.getCategory(item.name))) result.add(item)
            }
            return result
        }

        fun List<InstallablePackageInfo>.applySearchQueryFilter(query: String): List<InstallablePackageInfo> {
            if (query.isBlank() || query.isEmpty()) return emptyList()

            val result = mutableListOf<InstallablePackageInfo>()
            val items = this
            items.forEach { item ->
                val variant = item.packageInfo.selectedVariant
                if (item.name.contains(query, true) ||
                    variant.pkgName.contains(query, true) ||
                    variant.appName.contains(query, true)
                ) {
                    result.add(item)
                }
            }

            return result
        }

        fun updatableFromMap(list: Map<String, PackageInfo>): List<InstallablePackageInfo> {
            val result = mutableListOf<InstallablePackageInfo>()
            val value = list.values
            for (item in value) {
                if (item.installStatus is InstallStatus.Updatable
                    && item.downloadStatus !is DownloadStatus.Downloading
                ) {
                    result.add(InstallablePackageInfo(item.id, item))
                }
            }
            return result
        }

    }

    open class UiItemDiff(private val isDownloadUi: Boolean = false) :
        DiffUtil.ItemCallback<InstallablePackageInfo>() {

        override fun areItemsTheSame(
            oldItem: InstallablePackageInfo,
            newItem: InstallablePackageInfo
        ) = oldItem.name == newItem.name

        override fun areContentsTheSame(
            oldItem: InstallablePackageInfo,
            newItem: InstallablePackageInfo
        ): Boolean {

            /*PackageInfo#sessionInfo and PackageInfo#TaskInfo doesn't effect UI
                so it should be counted as content haven't changed*/

            val isChanged = oldItem.name == newItem.name &&
                    oldItem.packageInfo.selectedVariant == newItem.packageInfo.selectedVariant &&
                    oldItem.packageInfo.installStatus == newItem.packageInfo.installStatus &&
                    oldItem.packageInfo.id == newItem.packageInfo.id

            val oldStatus = oldItem.packageInfo.downloadStatus
            val newStatus = newItem.packageInfo.downloadStatus

            if (isDownloadUi) {
                return isChanged && oldStatus == newStatus
            }

            return isChanged && oldStatus?.isDownloading == newStatus?.isDownloading
        }
    }

}