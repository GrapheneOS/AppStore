package org.grapheneos.apps.client.item

import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import java.io.File

sealed class DownloadCallBack(
    val isSuccessFull: Boolean,
    open val genericMsg: String,
    val error: Exception?
) {

    companion object {
        fun DownloadCallBack.toUiMsg(): String {
            return genericMsg + if (!isSuccessFull && error != null) error.localizedMessage else ""
        }
    }

    data class Success(
        override val genericMsg: String = App.getString(R.string.DownloadedSuccessfully),
        val apks: List<File> = listOf()
    ) : DownloadCallBack(
        true,
        genericMsg,
        null
    )

    data class Canceled(val msg: String = App.getString(R.string.dfCanceled)) :
        DownloadCallBack(false, msg, null)

    data class IoError(val e: Exception) : DownloadCallBack(
        false,
        App.getString(R.string.dfIoError),
        e
    )

    data class SecurityError(val e: Exception) : DownloadCallBack(
        false,
        App.getString(R.string.dfSecurityError),
        e
    )

    data class UnknownHostError(val e: Exception) : DownloadCallBack(
        false,
        App.getString(R.string.dfUnknownHostError),
        e
    )
}