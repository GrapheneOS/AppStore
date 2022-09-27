package org.grapheneos.apps.client.utils

import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

fun NotificationManagerCompat.createAppsRelatedChannel(context: Context) {

    val seamlessUpdateGroup = NotificationChannelGroup(
        App.SEAMLESS_UPDATE_GROUP,
        context.getString(R.string.suGroupName)
    )
    seamlessUpdateGroup.description = context.getString(R.string.suGroupDescription)
    val installationFailedGroup = NotificationChannelGroup(
        App.INSTALLATION_FAILED_GROUP,
        context.getString(R.string.installationFailed)
    )
    createNotificationChannelGroups(
        listOf(
            seamlessUpdateGroup,
            installationFailedGroup
        )
    )


    val installationFailed = NotificationChannelCompat.Builder(
        App.INSTALLATION_FAILED_CHANNEL,
        NotificationManager.IMPORTANCE_HIGH
    ).setName(context.getString(R.string.installationFailed))
        .setVibrationEnabled(false)
        .setGroup(App.INSTALLATION_FAILED_GROUP)
        .setShowBadge(false)
        .setLightsEnabled(false)
        .build()

    val channelSeamlesslyUpdated = NotificationChannelCompat.Builder(
        App.SEAMLESSLY_UPDATED_CHANNEL,
        NotificationManager.IMPORTANCE_LOW
    ).setName(context.getString(R.string.nUpdatedTitle))
        .setDescription(context.getString(R.string.nUpdatedDescription))
        .setVibrationEnabled(false)
        .setGroup(App.SEAMLESS_UPDATE_GROUP)
        .setShowBadge(false)
        .setLightsEnabled(false)
        .build()

    val channelConformationNeeded = NotificationChannelCompat.Builder(
        App.SEAMLESS_UPDATE_INPUT_REQUIRED_CHANNEL,
        NotificationManager.IMPORTANCE_HIGH
    ).setName(context.getString(R.string.nUpdateAvailableTitle))
        .setDescription(context.getString(R.string.nUpdateAvailableDescription))
        .setVibrationEnabled(false)
        .setGroup(App.SEAMLESS_UPDATE_GROUP)
        .setShowBadge(false)
        .setLightsEnabled(false)
        .build()

    val channelAlreadyUpToDate = NotificationChannelCompat.Builder(
        App.ALREADY_UP_TO_DATE_CHANNEL,
        NotificationManager.IMPORTANCE_MIN
    ).setName(context.getString(R.string.nUpToDateTitle))
        .setDescription(context.getString(R.string.nUpToDateDescription))
        .setVibrationEnabled(false)
        .setGroup(App.SEAMLESS_UPDATE_GROUP)
        .setShowBadge(false)
        .setLightsEnabled(false)
        .build()

    val channelSeamlessUpdateFailed = NotificationChannelCompat.Builder(
        App.SEAMLESS_UPDATE_FAILED_CHANNEL,
        NotificationManager.IMPORTANCE_DEFAULT
    ).setName(context.getString(R.string.nUpdatesFailedTitle))
        .setDescription(context.getString(R.string.nUpdatesFailedDescription))
        .setVibrationEnabled(false)
        .setShowBadge(false)
        .setGroup(App.SEAMLESS_UPDATE_GROUP)
        .setLightsEnabled(false)
        .build()

    val channelBackgroundTask = NotificationChannelCompat.Builder(
        App.BACKGROUND_SERVICE_CHANNEL,
        NotificationManager.IMPORTANCE_LOW
    ).setName(context.getString(R.string.nBackgroundTaskTitle))
        .setDescription(context.getString(R.string.nBackgroundTaskDescription))
        .setVibrationEnabled(false)
        .setShowBadge(false)
        .setLightsEnabled(false)
        .build()

    createNotificationChannelsCompat(
        listOf(
            installationFailed,
            channelBackgroundTask,
            channelSeamlesslyUpdated,
            channelConformationNeeded,
            channelAlreadyUpToDate,
            channelSeamlessUpdateFailed
        )
    )
}
