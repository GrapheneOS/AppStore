package app.grapheneos.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Log
import androidx.core.content.edit
import androidx.core.os.postDelayed
import androidx.core.util.isEmpty
import androidx.core.util.size
import androidx.core.util.valueIterator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.grapheneos.apps.core.appContext
import app.grapheneos.apps.core.appResources
import app.grapheneos.apps.core.mainHandler
import app.grapheneos.apps.core.pkgManager
import app.grapheneos.apps.core.InstallTask
import app.grapheneos.apps.core.InstallerSessions
import app.grapheneos.apps.core.InstallerSessions.installerSessionMap
import app.grapheneos.apps.util.ActivityUtils
import app.grapheneos.apps.util.checkMainThread
import app.grapheneos.apps.util.forEachEntry
import app.grapheneos.apps.util.getPackageInfoOrNull
import app.grapheneos.apps.util.getSharedPreferences
import app.grapheneos.apps.util.intent
import app.grapheneos.apps.util.invokeOnCompletionOnMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import app.grapheneos.apps.core.PackageState
import app.grapheneos.apps.core.RPackageContainer
import app.grapheneos.apps.core.ReleaseChannel
import app.grapheneos.apps.core.Repo
import app.grapheneos.apps.core.RepoUpdateError
import app.grapheneos.apps.core.fetchRepo
import app.grapheneos.apps.core.getCachedRepo
import app.grapheneos.apps.core.prunePackageCache
import app.grapheneos.apps.util.getParcelableOrThrow
import app.grapheneos.apps.util.simpleName
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.retry.policy.binaryExponentialBackoff
import com.github.michaelbull.retry.result.retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// Tracks package-related state, responds to its changes and dispatches them to registered listeners
object PackageStates : LifecycleEventObserver {
    private val TAG = simpleName<PackageStates>()

    val map = HashMap<String, PackageState>()

    var repo: Repo = getCachedRepo(); private set
    var repoUpdateJob: Deferred<RepoUpdateError?>? = null; private set
    private var lastSuccessfulRepoUpdateCheck = -1L
    private var lastRepoUpdateResult: RepoUpdateError? = null

    private val installTasks = ArrayList<InstallTask>(7)
    var packageCachePruningJob: Deferred<Unit>? = null

    private var prevPackageStateId = 0L

    fun init() {
        checkMainThread()
        updateRepo(repo)

        InstallerSessions.init()

        // don't prune the cache immediately in case app process previously died without completing
        // the installation and the user is now about to try installing the same package again
        mainHandler.postDelayed(5.minutes.inWholeMilliseconds) {
            scheduleCachePruning()
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                checkMainThread()

                val TAG = "PkgChangeListener"

                if (Build.VERSION.SDK_INT >= 33) {
                    if (intent.action == Intent.ACTION_APPLICATION_LOCALE_CHANGED) {
                        val packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)!!
                        Log.d(TAG, "app locale change event for $packageName")
                        map[packageName]?.let {
                            it.pkgSpecificLocales = intent.extras!!.getParcelableOrThrow(Intent.EXTRA_LOCALE_LIST)
                            it.notifyListeners()
                            // TODO install language split immediately if it's available, also handle
                            //  global locale changes
                        }
                        return
                    }
                }

                val packageName = intent.data!!.schemeSpecificPart

                map[packageName]?.let {
                    it.osPackageInfo = pkgManager.getPackageInfoOrNull(packageName)
                    it.notifyListeners()
                }

                updateNumberOfOutdatedPackages()

                Log.d(TAG, "${intent.action} packageName $packageName")
            }
        }
        val filter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)

            if (Build.VERSION.SDK_INT >= 33) {
                addAction(Intent.ACTION_APPLICATION_LOCALE_CHANGED)
            }
        }
        appContext.registerReceiver(receiver, filter)
    }

    fun updateRepo(repo: Repo) {
        checkMainThread()

        repo.groups.values.forEach {
            it.releaseChannelOverride = maybeGetReleaseChannelOverride(it.name)
        }

//        val start = SystemClock.uptimeMillis()

        val leftoverPackages = ArraySet(map.keys)

        repo.packages.forEach { entry ->
            val pkgName = entry.key
            leftoverPackages.remove(pkgName)

            map.getOrPut(pkgName) {
                PackageState(pkgName, ++prevPackageStateId).apply {
                    releaseChannelOverride = maybeGetReleaseChannelOverride(pkgName)
                }
            }.apply {
                val container: RPackageContainer = entry.value
                val pkg = container.getPackage(preferredReleaseChannel(container))
                setRPackage(pkg)
                if (!pkg.common.isSharedLibrary) {
                    // PackageInfo is highly likely to be cached at this point, cache is populated
                    // during repo parsing.
                    // pkgManager.getInstalledPackages() is not cached and is much slower in
                    // almost all cases
                    osPackageInfo = pkgManager.getPackageInfoOrNull(pkgName)
                }
            }
        }

        leftoverPackages.forEach {
            // this package is missing from the new repo, drop its PackageState
            Log.d(TAG, "leftover package: $it")
            map.remove(it)
        }

//        Log.d("updateRepo", "took ${SystemClock.uptimeMillis() - start} ms")

        this.repo = repo

        dispatchAllStatesChanged()
        updateNumberOfOutdatedPackages()
    }

    fun requestRepoUpdateNoSuspend(force: Boolean = false) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            requestRepoUpdate(force)
        }
    }

    suspend fun requestRepoUpdateRetrying(force: Boolean = false, isManuallyRequested: Boolean = false): RepoUpdateError? {
        val (_, err) = retry(binaryExponentialBackoff(1_000, 30_000)) {
            val err = requestRepoUpdate(force, isManuallyRequested)
            if (err != null) Err(err) else Ok(Unit)
        }
        return err
    }

    suspend fun requestRepoUpdate(force: Boolean = false, isManuallyRequested: Boolean = false): RepoUpdateError? {
        checkMainThread()

        repoUpdateJob?.let {
            return it.await()
        }

        if (!force) {
            val timestampMs = SystemClock.elapsedRealtime()
            if (timestampMs - lastSuccessfulRepoUpdateCheck < 5000) {
                return null
            }
        }

        val currentRepo = repo

        val repoUpdateJob = CoroutineScope(Dispatchers.IO).async {
            var result: RepoUpdateError? = null
            val repo = try {
                fetchRepo(currentRepo)
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }
                Log.w(TAG, "unable to fetch repo", t)
                result = RepoUpdateError(t, isManuallyRequested)
                null
            }

            val job = this.coroutineContext[Job]

            withContext(Dispatchers.Main) {
                checkMainThread()
                check(repoUpdateJob === job)
                repoUpdateJob = null
                lastRepoUpdateResult = result
                if (repo != null) {
                    lastSuccessfulRepoUpdateCheck = SystemClock.elapsedRealtime()
                    if (repo !== currentRepo) {
                        updateRepo(repo)
                    }
                }
                dispatchRepoUpdateResult(result)
            }
            result
        }

        this.repoUpdateJob = repoUpdateJob
        return repoUpdateJob.await()
    }

    private var serviceStarted = false

    fun addInstallTask(task: InstallTask) {
        checkMainThread()

        installTasks.add(task)

        map[task.rPackage.packageName]!!.let {
            check(it.installTask == null)
            it.installTask = task
            dispatchStateChanged(it)
        }

        maybeScheduleUpdateLoop()
    }

    private var updateScheduled = false

    var updateLoopRunnableRunCount = 0; private set

    private val updateLoopRunnable = Runnable {
        check(updateScheduled)
        updateScheduled = false

//        Log.d(TAG, "updateLoopRunnable $updateLoopRunnableRunCount")

        installTasks.forEach {
            dispatchStateChanged(it.packageState)
        }

        installerSessionMap.valueIterator().forEach { pkgState ->
            dispatchStateChanged(pkgState)
        }

        updateDownloadFgServiceState()

        if (installTasks.size != 0 || installerSessionMap.size != 0) {
            scheduleUpdateLoop()
        }

        ++updateLoopRunnableRunCount
    }

    private fun updateDownloadFgServiceState() {
        if (installTasks.sumOf { it.downloadProgress.get() } < installTasks.sumOf { it.downloadTotal }) {
            if (serviceStarted || ActivityUtils.mostRecentResumedActivity() != null) {
                val notif = PackageDownloadFgService.createNotification(installTasks)
                if (notif != null) {
                    intent<PackageDownloadFgService>().let {
                        it.putExtra(EXTRA_NOTIFICATION, notif)
                        appContext.startForegroundService(it)
                    }
                    serviceStarted = true
                }
            }
        } else {
            PackageDownloadFgService.invalidateNotifStateCache()
            if (serviceStarted) {
                appContext.stopService(intent<PackageDownloadFgService>())
                serviceStarted = false
            }
        }
    }

    private const val UPDATE_LOOP_INTERVAL = 300L

    fun scheduleUpdateLoop() {
        check(!updateScheduled)
        mainHandler.postDelayed(updateLoopRunnable, UPDATE_LOOP_INTERVAL)
        updateScheduled = true
    }

    fun maybeScheduleUpdateLoop() {
        if (!updateScheduled) {
            scheduleUpdateLoop()
        }
    }

    fun completeInstallTask(task: InstallTask) {
        checkMainThread()

        check(installTasks.remove(task))

        task.packageState.let {
            check(it.installTask === task)
            it.installTask = null
            dispatchStateChanged(it)
        }
    }

    fun numberOfInstallTasks(): Int {
        checkMainThread()
        return installTasks.size
    }

    private var pendingCachePruning = false
    private var periodicCachePruningScheduled = false

    fun scheduleCachePruning() {
        checkMainThread()

        if (installTasks.isEmpty() && installerSessionMap.isEmpty()) {
            if (packageCachePruningJob == null) {
                CoroutineScope(Dispatchers.IO).let { scope ->
                    val job = scope.async {
                        prunePackageCache()
                    }
                    packageCachePruningJob = job
                    job.invokeOnCompletionOnMainThread {
                        packageCachePruningJob = null

                        if (!periodicCachePruningScheduled) {
                            mainHandler.postDelayed(6.hours.inWholeMilliseconds) {
                                periodicCachePruningScheduled = false
                                scheduleCachePruning()
                            }
                            periodicCachePruningScheduled = true
                        }
                    }
                }
            }
        } else {
            pendingCachePruning = true
        }
    }

    private val defaultReleaseChannelPrefsKey = appResources.getString(R.string.pref_key_default_release_channel)

    private val prefs = getSharedPreferences(R.string.pref_file_settings).also {
        it.registerOnSharedPreferenceChangeListener { prefs, key ->
            if (key == defaultReleaseChannelPrefsKey) {
                defaultReleaseChannel = ReleaseChannel.valueOf(prefs.getString(defaultReleaseChannelPrefsKey, null)!!)

                map.values.forEach {
                    it.updateRPackage()
                }

                dispatchAllStatesChanged()
            }
        }
    }

    // TODO: surface in UI
    var defaultReleaseChannel: ReleaseChannel = ReleaseChannel.valueOf(
        prefs.getString(defaultReleaseChannelPrefsKey, ReleaseChannel.stable.name)!!
    ); private set

    // shared prefs name is a leftover from the initial version
    private val preferredReleaseChannelOverrides = getSharedPreferences("app_channel")

    fun setPreferredChannelOverride(state: PackageState, ch: ReleaseChannel) {
        checkMainThread()

        val group = state.rPackage.common.group

        if (group != null) {
            preferredReleaseChannelOverrides.edit {
                putString(group.name, ch.name)
            }

            group.releaseChannelOverride = ch

            group.packages.forEach {
                val pkgState = getPackageState(it.packageName)
                pkgState.updateRPackage()
                pkgState.notifyListeners()
            }

        } else {
            preferredReleaseChannelOverrides.edit {
                putString(state.pkgName, ch.name)
            }

            state.releaseChannelOverride= ch
            state.updateRPackage()
            state.notifyListeners()
        }

        updateNumberOfOutdatedPackages()
    }

    fun maybeGetReleaseChannelOverride(name: String): ReleaseChannel? {
        val ch = preferredReleaseChannelOverrides.getString(name, null)
        if (ch != null) {
            return ReleaseChannel.valueOf(ch)
        }
        return null
    }

    interface StateListener {
        fun onPackageStateChanged(state: PackageState) = Unit
        fun onAllPackageStatesChanged(states: Map<String, PackageState>) = Unit
        fun onNumberOfOutdatedPackagesChanged(value: Int) = Unit
        fun onRepoUpdateResult(error: RepoUpdateError?) = Unit
    }

    private val listeners = ArrayMap<LifecycleOwner, StateListener>()

    private fun dispatchToStartedListeners(block: (StateListener) -> Unit) {
        checkMainThread()
        mainHandler.post {
            listeners.forEachEntry { owner, listener ->
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    block(listener)
                }
            }
        }
    }

    // TODO: delay for a few hundred milliseconds if previous update was just now dispatched
    //  to make sure there's at teast half a second between UI updates, drop update entirely if
    //  another one was dispatched in the meantime
    fun dispatchStateChanged(state: PackageState) {
        dispatchToStartedListeners {
            it.onPackageStateChanged(state)
        }
    }

    fun dispatchAllStatesChanged() {
        dispatchToStartedListeners {
            it.onAllPackageStatesChanged(map)
        }
    }

    fun dispatchRepoUpdateResult(result: RepoUpdateError?) {
        dispatchToStartedListeners {
            it.onRepoUpdateResult(result)
        }
    }

    fun addListener(owner: LifecycleOwner, listener: StateListener) {
        checkMainThread()
        check(!listeners.containsKey(owner))
        check(owner.lifecycle.currentState < Lifecycle.State.STARTED)
        listeners[owner] = listener
        owner.lifecycle.addObserver(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_DESTROY) {
            check(listeners.containsKey(source))
            check(listeners.remove(source) != null)
        } else if (event == Lifecycle.Event.ON_START) {
            listeners[source]!!.let {
                it.onAllPackageStatesChanged(map)
                it.onRepoUpdateResult(lastRepoUpdateResult)
                it.onNumberOfOutdatedPackagesChanged(numberOfOutdatedPackages)
            }
        }
    }

    var numberOfOutdatedPackages = 0; private set

    fun updateNumberOfOutdatedPackages() {
        val count = map.values.count { it.isEligibleForBulkUpdate() }

        if (count != numberOfOutdatedPackages) {
            numberOfOutdatedPackages = count
            dispatchToStartedListeners {
                it.onNumberOfOutdatedPackagesChanged(numberOfOutdatedPackages)
            }
        }
    }

    fun maybeGetPackageState(pkgName: String): PackageState? = map[pkgName]

    fun maybeGetPackageLabel(pkgName: String): String? = maybeGetPackageState(pkgName)?.rPackage?.label

    fun getPackageState(pkgName: String): PackageState {
        return map[pkgName] ?: throw IllegalStateException("missing PackageState for $pkgName")
    }

    fun onResourceConfigChanged() {
        map.values.forEach {
            // list of APKs to download depends on resource configuration
            it.cachedDownloadSize = null
        }
    }
}
