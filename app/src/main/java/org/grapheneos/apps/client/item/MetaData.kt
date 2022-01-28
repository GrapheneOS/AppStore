package org.grapheneos.apps.client.item

import android.content.Context
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.ui.mainScreen.ChannelPreferenceManager
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper.Companion.getDownloadRootDir
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper.Companion.getResultDir
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper.Companion.getResultRootDir
import java.io.File

data class MetaData(
    val timestamp: Long,
    val packages: Map<String, Package>
) {

    fun cleanOldFiles(context: Context) {
        packages.entries.forEach { entry ->
            val channelPref = ChannelPreferenceManager.getPackageChannel(context, entry.key)
            entry.value.apply {
                val channelVariant =
                    variants[channelPref] ?: variants[App.getString(R.string.channel_default)]!!

                channelVariant.apply {
                    getResultRootDir(context).cleanOldFiles(getResultDir(context))
                }

                channelVariant.apply {
                    getDownloadRootDir(context).cleanOldFiles(getResultDir(context))
                }
            }
        }

    }

    private fun File.cleanOldFiles(currentFile: File) {
        if (exists()) {
            list()?.forEach { path ->
                val dir = File("${absolutePath}/$path")
                if (currentFile.absolutePath != dir.absolutePath) {
                    dir.deleteRecursively()
                }
            }
        }
    }

}