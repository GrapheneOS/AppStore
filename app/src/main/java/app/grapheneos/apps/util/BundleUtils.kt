package app.grapheneos.apps.util

import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import app.grapheneos.apps.ApplicationImpl

inline fun <reified T : Number> Bundle.maybeGetNumber(key: String): T? {
    // getInt(), getLong() etc return 0 when entry is missing, which is ambiguous when 0 is a valid value
    @Suppress("DEPRECATION")
    return get(key) as T?
}

inline fun <reified T : Number> Bundle.getNumber(key: String): T = maybeGetNumber(key)!!

fun Bundle.maybeGetBool(key: String): Boolean? {
    @Suppress("DEPRECATION")
    return get(key) as Boolean?
}

fun Bundle.getBool(key: String): Boolean = maybeGetBool(key)!!

inline fun <reified T : Parcelable> Bundle.maybeGetParcelable(key: String): T? {
    if (Build.VERSION.SDK_INT >= 34) {
        return getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        return getParcelable(key)
    }
}

inline fun <reified T : Parcelable> Bundle.getParcelableOrThrow(key: String): T = maybeGetParcelable<T>(key)!!

inline fun <reified T : Parcelable> Bundle.maybeGetParcelableArray(key: String): Array<T>? {
    if (Build.VERSION.SDK_INT >= 34) {
        return getParcelableArray(key, T::class.java)
    } else {
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        return getParcelableArray(key) as Array<T>?
    }
}

inline fun <reified T : Parcelable> Bundle.getParcelableArrayOrThrow(key: String): Array<T> =
    maybeGetParcelableArray(key)!!

fun <T : Parcelable> Bundle.putParcelable2(key: String, obj : T) {
    putByteArray(key, marshallParcelable(obj))
}

inline fun <reified T : Parcelable> Bundle.maybeGetParcelable2(key: String): T? {
    val bytes = getByteArray(key) ?: return null
    return unmarshallParcelable(bytes)
}

// {get,put}Parcelable2 API allows to put custom Parcelable objects into Bundle that is
// sent to process that doesn't have classloader that is able to instantiate that Parcelable.
// When this happens with regular {get,put}Parcelable APIs, entire Bundle contents might
// be discarded
inline fun <reified T : Parcelable> Bundle.getParcelable2(key: String): T {
    return unmarshallParcelable(getByteArray(key)!!)
}

fun <T : Parcelable> marshallParcelable(obj: T): ByteArray {
    val parcel = Parcel.obtain()
    try {
        parcel.writeParcelable(obj, 0)
        return parcel.marshall()
    } finally {
        parcel.recycle()
    }
}

inline fun <reified T : Parcelable> unmarshallParcelable(bytes: ByteArray): T {
    return unmarshallParcelableInner(bytes, T::class.java) as T
}

fun unmarshallParcelableInner(bytes: ByteArray, type: Class<out Parcelable>): Parcelable {
    val parcel = Parcel.obtain()
    try {
        parcel.unmarshall(bytes, 0, bytes.size)
        parcel.setDataPosition(0)
        val classLoader = type.classLoader
        val obj = if (Build.VERSION.SDK_INT >= 34) {
            parcel.readParcelable(classLoader, type)
        } else {
            @Suppress("DEPRECATION")
            parcel.readParcelable(classLoader)
        }
        check(obj!!.javaClass === type)
        if (obj is Bundle) {
            obj.classLoader = ApplicationImpl::class.java.classLoader
        }
        return obj
    } finally {
        parcel.recycle()
    }
}
