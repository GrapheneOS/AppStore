package org.grapheneos.apps.client.utils

class AppSourceHelper {

    companion object {
        private const val gos = "GrapheneOS"
        private const val google = "Google (mirror)"
        private const val buildByGos = "Third party apps"


        /*package name, category name, */
        private val listOfKnownApps = mutableMapOf<String, String>().apply {
            put("app.attestation.auditor", gos)
            put("org.grapheneos.pdfviewer", gos)
            put("app.grapheneos.camera", gos)

            put("com.google.android.gms", google)
            put("com.google.android.gsf", google)
            put("com.android.vending", google)

        }

        fun getCategoryName(pkgName: String) = listOfKnownApps.getOrDefault(pkgName, buildByGos)
    }

}