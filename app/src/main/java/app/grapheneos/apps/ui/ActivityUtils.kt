package app.grapheneos.apps.util

import android.app.Activity
import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.IdRes
import androidx.core.os.postDelayed
import app.grapheneos.apps.ApplicationImpl
import app.grapheneos.apps.Notifications
import app.grapheneos.apps.core.mainHandler
import app.grapheneos.apps.core.notificationManager
import app.grapheneos.apps.show
import app.grapheneos.apps.ui.MainActivity
import java.util.ArrayList
import java.util.function.Supplier

object ActivityUtils {
    private val resumedActivities = ArrayList<Activity>()

    private val pendingActions = ArrayList<PendingAction>()

    fun init(activeNotifications: Array<StatusBarNotification>) {
        // restore PendingActions made by previous process launches
        for (sbn: StatusBarNotification in activeNotifications.sortedBy { it.id }) {
            val pendingAction = PendingAction.fromBundle(sbn.notification.extras) ?: continue

            if (pendingAction.transient) {
                Notifications.cancel(pendingAction.notificationId)
                continue
            }

            pendingActions.add(pendingAction)
        }
    }

    fun addPendingAction(action: PendingAction, notification: Supplier<Notification.Builder>) {
        checkMainThread()

        mostRecentResumedActivity()?.let {
            action.launch(it)
            return
        }

        // Sometimes pending action is added right before the activity becomes visible again, eg
        // when an OS activity launched by us launches our PendingIntent and immediately finishes.
        // In this case, notification would be shown and dismissed immediately, which is undesirable.
        // Delay adding of PendingAction for a bit as a workaround
        mainHandler.postDelayed(300L) {
            mostRecentResumedActivity()?.let {
                action.launch(it)
                return@postDelayed
            }

            pendingActions.add(action)

            notification.get().apply {
                setStyle(Notification.BigTextStyle())
                // otherwise, code that parses PendingActions from the list of active notifications
                // will not work
                setAutoCancel(false)

                if (action.notificationId == 0) {
                    action.notificationId = Notifications.generateId()
                }
                extras.putParcelable2(PendingAction.KEY_BUNDLE, action.toBundle())
                show(action.notificationId)
            }
        }
    }

    // Use onResume/onPause instead of onStart/onStop, because the latter are not executed when
    // non-full-screen activity is launched by us. Launching another activity in this state (paused
    // but not stopped) may interfere with that activity (it'd become stopped)
    fun onActivityResumedOrPaused(activity: Activity, resumed: Boolean) {
        checkMainThread()
        if (resumed) {
            check(!resumedActivities.contains(activity))

            if (activity is MainActivity) {
                var launchedActivity = false

                pendingActions.removeAll {
                    if (it is PendingDialog) {
                        it.launch(activity)
                        true
                    } else {
                        check(it is PendingActivityIntent)
                        if (!launchedActivity) {
                            it.launch(activity)
                            launchedActivity = true
                            true
                        } else {
                            false
                        }
                    }
                }

                if (launchedActivity) {
                    // don't add to resumedActivities to avoid race: start of intent will pause the
                    // activity, but in a racy way: it may try to launch another PendingAction
                    return
                }
            }

            resumedActivities.add(activity)
        } else {
            resumedActivities.remove(activity)
        }
    }

    fun maybeAddPendingActionFromIntent(intent: Intent) {
        val pa = PendingAction.fromIntent(intent)
        if (pa != null) {
            pendingActions.removeAll {
                it.notificationId == pa.notificationId
            }
            pendingActions.add(pa)
        }
    }

    // TODO in cases where action originates from activity, store activity identifier and pass it
    //  here. Activity identifier should remain the same when activity is recreated
    fun mostRecentResumedActivity(): MainActivity? {
        checkMainThread()
        return resumedActivities.lastOrNull() as MainActivity?
    }
}

sealed class PendingAction {
    // whether to restore it after process restart
    var transient: Boolean = false
    var notificationId: Int = 0

    fun toBundle(): Bundle {
        val b = toBundleInner()
        b.putInt(KEY_NOTIFICATION_ID, notificationId)
        b.putBoolean(KEY_TRANSIENT, transient)
        return b
    }

    protected abstract fun toBundleInner(): Bundle

    fun launch(activity: MainActivity) {
        execute(activity)
        if (notificationId != 0) {
            notificationManager.cancel(notificationId)
        }
    }

    protected abstract fun execute(activity: MainActivity)

    companion object {
        val KEY_BUNDLE = className<PendingAction>()

        const val KEY_TYPE = "type"
        const val TYPE_INTENT = 0
        const val TYPE_DIALOG = 1

        const val KEY_INTENT = "intent"
        const val KEY_DIALOG_ARGS = "dialog_args"
        const val KEY_DIALOG_ID = "dialog_id"
        const val KEY_NOTIFICATION_ID = "notification_id"
        const val KEY_TRANSIENT = "transient"

        fun fromIntent(intent: Intent): PendingAction? {
            intent.extras?.let {
                return fromBundle(it)
            }
            return null
        }

        fun fromBundle(bundle: Bundle): PendingAction? {
            val b = bundle.maybeGetParcelable2<Bundle>(KEY_BUNDLE) ?: return null

            val type = b.getNumber<Int>(KEY_TYPE)
            val pa = if (type == TYPE_INTENT) {
                PendingActivityIntent(b.getParcelable2(KEY_INTENT))
            } else {
                check(type == TYPE_DIALOG)
                PendingDialog(b.getNumber(KEY_DIALOG_ID), b.getParcelable2(KEY_DIALOG_ARGS))
            }
            pa.notificationId = b.getNumber(KEY_NOTIFICATION_ID)
            pa.transient = b.getBool(KEY_TRANSIENT)
            return pa
        }
    }
}

class PendingActivityIntent(val intent: Intent) : PendingAction() {
    override fun toBundleInner() = Bundle().apply {
        putInt(KEY_TYPE, TYPE_INTENT)
        putParcelable2(KEY_INTENT, intent)
    }

    override fun execute(activity: MainActivity) {
        activity.startActivity(intent)
    }
}

class PendingDialog(@param:IdRes val id: Int, val args: Bundle) : PendingAction() {
    override fun toBundleInner() = Bundle().apply {
        putInt(KEY_TYPE, TYPE_DIALOG)
        putInt(KEY_DIALOG_ID, id)
        putParcelable2(KEY_DIALOG_ARGS, args)
    }

    override fun execute(activity: MainActivity) {
        activity.navController.navigate(id, args)
    }
}
