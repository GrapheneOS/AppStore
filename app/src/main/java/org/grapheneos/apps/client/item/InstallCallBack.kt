package org.grapheneos.apps.client.item

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

sealed class InstallCallBack(
    open val sessionId: Int,
    val description: String,
    val unresolvableError: Boolean = true
) : Parcelable {

    @Parcelize
    data class Success(override val sessionId: Int) :
        InstallCallBack(
            description = App.getString(R.string.icSuccess),
            unresolvableError = false,
            sessionId = sessionId
        )

    @Parcelize
    data class Failure(override val sessionId: Int) :
        InstallCallBack(
            sessionId = sessionId,
            description = App.getString(R.string.icUnknownError)
        )

    @Parcelize
    data class FailureAborted(override val sessionId: Int) :
        InstallCallBack(
            sessionId = sessionId,
            description = App.getString(R.string.icAborted)
        )

    @Parcelize
    data class FailureStorage(override val sessionId: Int) :
        InstallCallBack(
            sessionId = sessionId,
            description = App.getString(R.string.icStorage)
        )

    @Parcelize
    data class FailureInvalid(override val sessionId: Int) :
        InstallCallBack(
            sessionId = sessionId,
            description = App.getString(R.string.icInvalid)
        )

    @Parcelize
    data class Conflict(override val sessionId: Int) :
        InstallCallBack(
            sessionId = sessionId,
            description = App.getString(R.string.icConflict)
        )

    @Parcelize
    data class Blocked(override val sessionId: Int) :
        InstallCallBack(
            sessionId = sessionId,
            description = App.getString(R.string.icBlocked)
        )

    @Parcelize
    data class Incompatible(override val sessionId: Int) :
        InstallCallBack(
            sessionId = sessionId,
            description = App.getString(R.string.icIncompatible)
        )

    @Parcelize
    data class UserActionPending(override val sessionId: Int) :
        InstallCallBack(
            sessionId = sessionId,
            description = App.getString(R.string.icPending)
        )

}