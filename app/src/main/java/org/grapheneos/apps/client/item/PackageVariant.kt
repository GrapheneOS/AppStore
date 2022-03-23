package org.grapheneos.apps.client.item

data class PackageVariant(
    val appName: String,
    val pkgName: String,
    val type: String,
    val packagesInfo: Map<String, String>, //package and hash
    val versionCode: Int,
    val dependencies: List<String> = listOf(),
    val isSystemApp: Boolean = false,
    val originalPkgName: String? = null
)