package org.grapheneos.apps.client.ui.mainScreen

import android.content.Context
import android.content.SharedPreferences

import org.grapheneos.apps.client.R

class ChannelPreferenceManager {
    companion object {
        private fun getAppChannelPreferences(context: Context): SharedPreferences {
            return context.applicationContext
                .getSharedPreferences("app_channel", Context.MODE_PRIVATE)
        }

        fun savePackageChannel(context: Context, pkgName: String, variant: String = "stable") {
            getAppChannelPreferences(context).edit().putString(pkgName, variant).apply()
        }

        fun getPackageChannel(context: Context, pkgName: String): String {
            val defaultChannel = context.getString(R.string.channel_default)
            return getAppChannelPreferences(context)
                .getString(pkgName, defaultChannel) ?: defaultChannel
        }

    }
}