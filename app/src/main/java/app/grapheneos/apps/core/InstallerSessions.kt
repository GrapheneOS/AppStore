package app.grapheneos.apps.core

import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams
import android.util.Log
import android.util.SparseArray
import app.grapheneos.apps.BuildConfig
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.util.checkMainThread
import app.grapheneos.apps.util.checkNotMainThread
import app.grapheneos.apps.util.getPackageInfoOrNull
import app.grapheneos.apps.util.simpleName
import kotlinx.coroutines.sync.Semaphore

// Tracks lifecycles of PackageInstaller sessions, including across process restarts
object InstallerSessions {
    private val TAG = simpleName<InstallerSessions>()

    val installerSessionMap = SparseArray<PackageState>()

    val sessionCallback = object : PackageInstaller.SessionCallback() {
        private val TAG = "InstallerSessionCallback"

        override fun onCreated(sessionId: Int) = Unit
        override fun onBadgingChanged(sessionId: Int) = Unit
        override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit

        override fun onProgressChanged(sessionId: Int, progress: Float) {
            val state = installerSessionMap[sessionId]
            if (state != null) {
                if (state.waitingForPendingUserAction) {
                    state.waitingForPendingUserAction = false
                    state.notifyListeners()
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onProgressChanged $sessionId: $progress")
            }
        }

        override fun onFinished(sessionId: Int, success: Boolean) {
            if (maybeRemoveSession(sessionId)) {
                activePkgInstallerSessionsSemaphore.release()
                Log.d(TAG, "completed session $sessionId, success: $success")
            } else if (maybeRemoveMultiInstallSesssion(sessionId)) {
                activePkgInstallerSessionsSemaphore.release()
                Log.d(TAG, "completed multi-install session $sessionId, success: $success")
            } else {
                Log.d(TAG, "completed unknown session $sessionId")
            }
        }
    }

    fun init() {
        // Important to register before getMySessions() call below. Otherwise, sessions that are
        // picked up from previous process launches might never get their session completion callback
        pkgInstaller.registerSessionCallback(sessionCallback, mainHandler)

        // PackageInstaller requires session package name to match manifest package name, even
        // if the package will be renamed via original-package system
        fun getSessionPackageName(sessionInfo: PackageInstaller.SessionInfo) =
            PackageStates.repo.translateManifestPackageName(sessionInfo.appPackageName!!)

        for (sessionInfo in pkgInstaller.getMySessions()) {
            Log.d(TAG, "pkg " + sessionInfo.appPackageName + " $sessionInfo" + " isChild " + sessionInfo.hasParentSessionId() + " isMulti  " + sessionInfo.isMultiPackage)
            if (!sessionInfo.isCommitted) {
                abandonSession(sessionInfo)
                continue
            }

            fun getSessionPackageState(sessionInfo: PackageInstaller.SessionInfo) = PackageStates.map[getSessionPackageName(sessionInfo)]

            fun isSessionWaitingForUserAction(sessionInfo: PackageInstaller.SessionInfo): Boolean {
                return if (isPrivilegedInstaller) {
                    val pkgName = getSessionPackageName(sessionInfo)
                    pkgManager.getPackageInfoOrNull(pkgName, 0) == null
                } else {
                    // Undocumented constant, but it has been stable for a long time:
                    // 0.8 means "pending user action", "0.9" means installation resumed after
                    // user action. No official API for this.
                    sessionInfo.progress < 0.9f
                }
            }

            fun addPreviousSession(sessionInfo: PackageInstaller.SessionInfo, state: PackageState?) {
                check(activePkgInstallerSessionsSemaphore.tryAcquire())
                val id = sessionInfo.sessionId
                if (state != null) {
                    addSession(id, state)
                    Log.d(TAG, "picked up previous session $id for ${state.pkgName}")
                } else {
                    addMultiInstallSession(id)
                    Log.d(TAG, "picked up previous multi-package session $id")
                }
            }

            if (sessionInfo.isMultiPackage) {
                val childSessionInfos = sessionInfo.childSessionIds.map {
                    pkgInstaller.getSessionInfo(it)
                }.filterNotNull()

                if (childSessionInfos.any { isSessionWaitingForUserAction(it) }) {
                    abandonSession(sessionInfo)
                    continue
                }

                for (childSessionInfo in childSessionInfos) {
                    val state = getSessionPackageState(childSessionInfo)
                    if (state != null) {
                        addPreviousSession(childSessionInfo, state)
                    } // child sessions can't be abandoned
                }

                addPreviousSession(sessionInfo, null)
                continue
            }

            val pkgName = getSessionPackageName(sessionInfo)

            if (isSessionWaitingForUserAction(sessionInfo)) {
                // unable to retrieve the pending user action intent, nor determine reliably
                // whether it was launched and/or interacted with
                abandonSession(sessionInfo)
                continue
            }

            val state = PackageStates.maybeGetPackageState(pkgName)

            if (state != null) {
                addPreviousSession(sessionInfo, state)
            } else {
                abandonSession(sessionInfo)
            }
        }
    }

    private fun addSession(sessionId: Int, pkgState: PackageState) {
        checkMainThread()

        check(!installerSessionMap.contains(sessionId))
        check(!pkgState.hasInstallerSession())

        pkgState.pkgInstallerSessionId = sessionId
        installerSessionMap[sessionId] = pkgState

        PackageStates.maybeScheduleUpdateLoop()
    }

    private val multiInstallSessions = SparseArray<Unit>()

    private fun addMultiInstallSession(sessionId: Int) {
        checkMainThread()
        check(!multiInstallSessions.contains(sessionId))
        multiInstallSessions[sessionId] = Unit
    }

    fun maybeRemoveMultiInstallSesssion(sessionId: Int): Boolean {
        checkMainThread()
        if (multiInstallSessions.contains(sessionId)) {
            multiInstallSessions.remove(sessionId)
            return true
        }
        return false
    }

    private fun maybeRemoveSession(sessionId: Int): Boolean {
        checkMainThread()
        val state = installerSessionMap[sessionId]
        if (state == null) {
            // session is from some other installer or is being abandoned on launch
            return false
        }
        check(state.pkgInstallerSessionId == sessionId)
        state.pkgInstallerSessionId = PackageInstaller.SessionInfo.INVALID_ID

        installerSessionMap.remove(sessionId)
        state.waitingForPendingUserAction = false

        PackageStates.dispatchStateChanged(state)

        PackageStates.scheduleCachePruning()

        return true
    }

    suspend fun createMultiPackageSession(): Int {
        checkNotMainThread()

        val params = SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
            setMultiPackage()
        }

        val id = createSessionInner(params)
        Log.d(TAG, "created multi-package session $id")

        mainHandler.post {
            addMultiInstallSession(id)
        }

        return id
    }

    suspend fun createSession(params: SessionParams, pkgState: PackageState): Int {
        checkNotMainThread()
        val id = createSessionInner(params)
        Log.d(TAG, "created session $id for ${pkgState.pkgName}")
        mainHandler.post {
            // guaranteed to executed before session ending onFinished() session callback, because
            // both are executed on the same thread
            addSession(id, pkgState)
        }

        return id
    }

    private suspend fun createSessionInner(params: SessionParams): Int {
        activePkgInstallerSessionsSemaphore.acquire()
        val id = try {
            val id = pkgInstaller.createSession(params)
            check(id > 0)
            id
        } catch (e: Throwable) {
            activePkgInstallerSessionsSemaphore.release()
            throw e
        }
        return id
    }

    // As of Android 13, unprivileged installers are allowed to have up to 50 sessions at once,
    // and privileged ones up to 1024
    // (com.android.server.pm.PackageInstallerService.MAX_ACTIVE_SESSIONS_{NO,WITH}_PERMISSION)
    // Don't set the limit this high, it may lead to bad use of storage (APKs are kept by the OS
    // internally, and are pruned less eagerly than app caches)
    private val activePkgInstallerSessionsSemaphore = Semaphore(20)

    fun abandonSession(session: PackageInstaller.SessionInfo) = abandonSession(session.sessionId)

    fun abandonSession(sessionId: Int): Boolean {
        // sessionIds are not reused until OS reboots, no need to synchronize
        return try {
            pkgInstaller.abandonSession(sessionId)
            true
        } catch (e: Exception) {
            // abandonSession() docs say that it throws only SecurityException, but source code
            // shows that IllegalStateException might be thrown in some cases too
            Log.d(TAG, "", e)
            false
        }
    }
}
