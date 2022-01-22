package org.grapheneos.apps.client.item

import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

sealed class MetadataCallBack(
    val isSuccessFull: Boolean,
    val genericMsg: String,
    val error: Exception?
) {
    data class Success(val timestamp: Long) : MetadataCallBack(
        true,
        App.getString(R.string.syncedSuccessfully),
        null
    )

    data class SecurityError(val e: Exception) : MetadataCallBack(
        false,
        App.getString(R.string.sfSecurityError),
        e
    )

    data class JSONError(val e: Exception) : MetadataCallBack(
        false,
        App.getString(R.string.sfJSONError),
        e
    )

    data class DecoderError(val e: Exception) : MetadataCallBack(
        false,
        App.getString(R.string.sfDecoderError),
        e
    )

    data class UnknownHostError(val e: Exception) : MetadataCallBack(
        false,
        App.getString(R.string.sfUnknownHostError),
        e
    )
}