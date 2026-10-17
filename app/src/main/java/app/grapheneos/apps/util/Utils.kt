package app.grapheneos.apps.util

import android.content.Context
import android.net.Uri
import android.util.ArrayMap
import androidx.annotation.CheckResult
import androidx.annotation.StringRes
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.appResources
import app.grapheneos.apps.core.mainHandler
import app.grapheneos.apps.core.mainThread
import app.grapheneos.apps.core.selfPkgName
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

fun checkMainThread() {
    check(Thread.currentThread() === mainThread)
}

fun checkNotMainThread() {
    check(Thread.currentThread() !== mainThread)
}

// a variant of kotlin.io.copyTo that checks for job cancellation and publishes progress updates
@Throws(GeneralSecurityException::class, IOException::class)
fun InputStream.copyTo2(numBytes: Long, out: OutputStream, job: Job?, debugInputName: String, progress: AtomicLong? = null, bufSize: Int = 16 * 1024) {
    val buf = ByteArray(bufSize)
    var total = 0L

    while (true) {
        job?.ensureActive()
        val bufLen = this.read(buf)
        if (bufLen == -1) {
            break
        }
        check(bufLen > 0)

        if (total + bufLen > numBytes) {
            throw GeneralSecurityException("copyTo2: input returned more data than expected (expected $numBytes bytes); input: $debugInputName")
        }

        job?.ensureActive()
        out.write(buf, 0, bufLen)
        total += bufLen

        progress?.addAndGet(bufLen.toLong())
    }
    if (total != numBytes) {
        throw IOException("copyTo2: input didn't return enough data (expected $numBytes bytes); input: $debugInputName")
    }
}

@CheckResult
fun checkSha256(fd: FileDescriptor, size: Long, required: ByteArray): Boolean {
    val sha256 = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalRead = 0L
    FileInputStream(fd).use {
        while (true) {
            val numRead = it.read(buf)
            if (numRead == -1) {
                if (totalRead != size) {
                    return false
                }
                break
            }
            check(numRead > 0)
            totalRead += numRead
            if (totalRead > size) {
                return false
            }
            sha256.update(buf, 0, numRead)
        }
    }
    return sha256.digest().contentEquals(required)
}

fun Boolean.toInt() = if (this) 1 else 0
fun Int.isEven() = (this and 1) == 0
fun Int.isOdd() = (this and 1) != 0

inline val Int.kilobytes get() = this * 1_000L
inline val Int.megabytes get() = this * 1_000_000L
inline val Int.gigabytes get() = this * 1_000_000_000L

fun Deferred<Unit>.invokeOnCompletionOnMainThread(block: (t: Throwable?) -> Unit) {
    invokeOnCompletion { t: Throwable? ->
        mainHandler.post {
            block(t)
        }
    }
}

fun JSONArray.asStringList(): List<String> {
    return object : AbstractList<String>() {
        override val size: Int
            get() = length()

        override fun get(index: Int) = getString(index)
    }
}

inline fun <K, V> ArrayMap<K, V>.forEachEntry(block : (K, V) -> Unit) {
    val len = size
    for (i in 0 until len) {
        block(keyAt(i), valueAt(i))
    }
}

inline fun <K, V> ArrayMap<K, V>.forEachKey(block : K.() -> Unit) {
    val len = size
    for (i in 0 until len) {
        keyAt(i).block()
    }
}

inline fun <K, V> ArrayMap<K, V>.forEachValue(block : V.() -> Unit) {
    val len = size
    for (i in 0 until len) {
        valueAt(i).block()
    }
}

fun packageUri(pkgName: String) = Uri.fromParts("package", pkgName, null)

fun selfPackageUri() = packageUri(selfPkgName)

fun getSharedPreferences(name: String) = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
fun getSharedPreferences(@StringRes name: Int) = getSharedPreferences(appResources.getString(name))

inline fun <reified T> className() = T::class.java.name
inline fun <reified T> simpleName() = T::class.java.simpleName


