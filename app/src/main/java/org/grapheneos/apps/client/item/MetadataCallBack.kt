package org.grapheneos.apps.client.item

import org.bouncycastle.util.encoders.DecoderException
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.json.JSONException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLHandshakeException

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

    companion object {
        fun fromException(e: Exception): MetadataCallBack {
            return when (e) {
                is GeneralSecurityException -> SecurityError(e)
                is JSONException -> JSONError(e)
                is DecoderException -> DecoderError(e)
                is UnknownHostException -> UnknownHostError(e)
                is SSLHandshakeException -> SecurityError(e)
                is ConnectException -> UnknownHostError(e)
                else -> throw e
            }
        }
    }

}