package org.grapheneos.apps.client.ui.mainScreen

import android.content.Context
import android.content.SharedPreferences
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr

class ChannelPreferenceManager(
    private val context: Context,
    onDefaultChannelChange: (channel: String) -> Unit
) {

    private val appChannelPrefs: SharedPreferences
        get() {
            return context.applicationContext.getSharedPreferences(
                context.getString(R.string.appChannel),
                Context.MODE_PRIVATE
            )
        }
    private val globalPreferences: SharedPreferences
        get() {
            return context.applicationContext.getSharedPreferences(
                JobPsfsMgr.AUTO_UPDATE_PREFERENCE,
                Context.MODE_PRIVATE
            )
        }
    val defaultChannel: String
        get() {
            val defaultChannel = context.getString(R.string.channel_default)
            return globalPreferences.getString(
                context.getString(R.string.defaultChannelPreference),
                defaultChannel
            ) ?: defaultChannel
        }
    private val onGlobalPreferencesChange =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == context.getString(R.string.defaultChannelPreference)) {
                onDefaultChannelChange(defaultChannel)
            }
        }

    init {
        globalPreferences.registerOnSharedPreferenceChangeListener(onGlobalPreferencesChange)
    }

    fun savePackageChannel(
        pkgName: String,
        channel: String = context.getString(R.string.channel_default)
    ) {
        appChannelPrefs.edit().putString(pkgName, channel).apply()
    }

    fun getPackageChannelOrDefault(pkgName: String): String {
        return getPackageChannelOrNull(pkgName) ?: defaultChannel
    }

    fun getPackageChannelOrNull(pkgName: String): String? {
        return appChannelPrefs.getString(pkgName, null)
    }

    fun resetPackageChannel(pkgName: String) {
        appChannelPrefs.edit().remove(pkgName).apply()
    }
}
