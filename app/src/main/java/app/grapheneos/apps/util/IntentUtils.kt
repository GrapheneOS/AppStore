package app.grapheneos.apps.util

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import app.grapheneos.apps.core.appContext
import java.util.UUID

inline fun <reified T : Parcelable> getParcelableExtra(intent: Intent, name: String): T? {
    return if (Build.VERSION.SDK_INT >= 34) {
        intent.getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(name) as T?
    }
}

inline fun <reified T : Parcelable> getParcelableArrayListExtra(intent: Intent, name: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= 34) {
        intent.getParcelableArrayListExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra(name)
    }
}

inline fun <reified T> intent() = Intent(appContext, T::class.java)

inline fun <reified T> componentName() = ComponentName(appContext, T::class.java)

fun getPendingUniqueActivityIntent(intent: Intent, flags: Int): PendingIntent {
    intent.identifier = UUID.randomUUID().toString()
    return PendingIntent.getActivity(appContext, 0, intent, flags)
}
