package org.grapheneos.apps.client.item

import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

sealed class DownloadStatus(
    val isDownloading: Boolean = true,
    open val status: String
) {

    data class Failed(
        val errorMsg: String
    ) : DownloadStatus(
        false,
        App.getString(R.string.failed)
    )

    data class Downloading(
        override val status: String = App.getString(R.string.downloading),
        val downloadSize: Int, //KB
        val downloadedSize: Int, //KB
        val downloadedPercent: Double,
        val completed: Boolean
    ) : DownloadStatus(
        true,
        App.getString(R.string.downloading)
    )

}