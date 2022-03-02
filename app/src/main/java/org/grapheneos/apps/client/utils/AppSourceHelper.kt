package org.grapheneos.apps.client.utils

import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

class AppSourceHelper {

    sealed class BuildType(open val title: String) {
        data class GrapheneOs(override val title: String = App.getString(R.string.grapheneOS)) :
            BuildType(title)

        data class GoogleMirror(override val title: String = App.getString(R.string.googleMirror)) :
            BuildType(title)

        data class BuildByGrapheneOs(override val title: String = App.getString(R.string.grapheneOSBuild)) :
            BuildType(title)
    }

    companion object {
        val gos = BuildType.GrapheneOs()
        val google = BuildType.GoogleMirror()
        val buildByGos = BuildType.BuildByGrapheneOs()

        /*package name, category name, */
        private val listOfKnownApps = mutableMapOf<String, BuildType>().apply {
            put("app.attestation.auditor", gos)
            put("app.grapheneos.apps", gos)
            put("app.grapheneos.camera", gos)
            put("app.grapheneos.pdfviewer", gos)

            put("com.google.android.gms", google)
            put("com.google.android.gsf", google)
            put("com.android.vending", google)

        }

        fun getCategory(pkgName: String) =
            listOfKnownApps.getOrDefault(pkgName, BuildType.BuildByGrapheneOs())

        fun getCategoryName(pkgName: String) = getCategory(pkgName).title
    }

}
