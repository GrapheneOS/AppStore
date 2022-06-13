package org.grapheneos.apps.client.item

data class PackageVariant(
    val appName: String,
    val type: String,
    val packagesInfo: Map<String, String>, //package and hash
    val versionCode: Long,
    val dependencies: List<String> = listOf(),
    val originalPkgName: String? = null
)
