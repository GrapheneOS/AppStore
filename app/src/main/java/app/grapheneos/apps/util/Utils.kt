package app.grapheneos.apps.util

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.UserManager
import android.util.ArrayMap
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.appResources
import app.grapheneos.apps.core.isPrivilegedInstaller
import app.grapheneos.apps.core.mainHandler
import app.grapheneos.apps.core.mainThread
import app.grapheneos.apps.core.selfPkgName
import app.grapheneos.apps.core.userManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

fun checkMainThread() {
    check(Thread.currentThread() === mainThread)
}

fun checkNotMainThread() {
    check(Thread.currentThread() !== mainThread)
}

// a variant of kotlin.io.copyTo that checks for job cancellation and publishes progress updates
fun InputStream.copyTo2(out: OutputStream, job: Job, progress: AtomicLong? = null, bufSize: Int = 16 * 1024): Long {
    val buf = ByteArray(bufSize)
    var total = 0L

    while (true) {
        job.ensureActive()
        val bufLen = this.read(buf)
        if (bufLen < 0) {
            break
        }

        job.ensureActive()
        out.write(buf, 0, bufLen)
        total += bufLen

        progress?.addAndGet(bufLen.toLong())
    }
    return total
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

fun Activity.hideKeyboard() {
    hideKeyboard(currentFocus ?: View(this))
}

private fun Context.hideKeyboard(view: View) {
    val inputMethodManager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
}
