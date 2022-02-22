package org.grapheneos.apps.client.utils.network

import android.content.Context
import org.grapheneos.apps.client.item.PackageVariant
import java.io.File


/*Metadata related files*/
fun Context.metadataVerifiedDir() =
    "${filesDir.absolutePath}/verified/metadata"

fun Context.metadataTmpDir() =
    "${cacheDir.absolutePath}/temporary/files/metadata"


/*Apks related files*/
fun PackageVariant.getResultRootDir(context: Context): File {
    val files = context.filesDir.absolutePath
    return File("${files}/downloads/packages/${pkgName}")
}

fun PackageVariant.getResultDir(context: Context): File {
    return File("${getResultRootDir(context)}/$versionCode")
}

fun PackageVariant.getDownloadRootDir(context: Context): File {
    val cacheDir = context.cacheDir.absolutePath
    return File("${cacheDir}/temporary/files/packages/${pkgName}")
}

fun PackageVariant.getDownloadDir(context: Context): File {
    return File("${getDownloadRootDir(context).absolutePath}/$versionCode")
}