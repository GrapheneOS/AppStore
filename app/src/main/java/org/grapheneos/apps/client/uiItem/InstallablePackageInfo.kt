package org.grapheneos.apps.client.uiItem

import androidx.recyclerview.widget.DiffUtil
import org.grapheneos.apps.client.item.PackageInfo

data class InstallablePackageInfo(
    val name: String,
    val packageInfo: PackageInfo
) {

    open class UiItemDiff : DiffUtil.ItemCallback<InstallablePackageInfo>() {

        override fun areItemsTheSame(
            oldItem: InstallablePackageInfo,
            newItem: InstallablePackageInfo
        ) = oldItem.name == newItem.name

        override fun areContentsTheSame(
            oldItem: InstallablePackageInfo,
            newItem: InstallablePackageInfo
        ): Boolean {
            return oldItem == newItem
        }
    }

}