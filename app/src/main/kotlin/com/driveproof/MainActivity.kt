package com.driveproof

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.FrameLayout
import com.driveproof.data.DEFAULT_PROFILE
import com.driveproof.data.ConsentStore
import com.driveproof.data.RecordingConsent
import com.driveproof.data.DriveDatabase
import com.driveproof.data.ExemptEventEntity
import com.driveproof.data.SceneEntity
import com.driveproof.data.ProofSnapshot
import com.driveproof.data.TripRepository
import com.driveproof.data.proofSnapshot
import com.driveproof.data.TripEntity
import com.driveproof.service.TripNotifications
import com.driveproof.service.TripService
import com.driveproof.source.live.FusedHeadingSource
import com.driveproof.model.Principle
import com.driveproof.ui.Ui
import com.driveproof.ui.consentPage
import com.driveproof.ui.proofPage
import com.driveproof.ui.monthlyReport
import com.driveproof.ui.historyLog
import com.driveproof.ui.principleDetail
import com.driveproof.ui.reasonLog
import com.driveproof.ui.scoringRules
import com.driveproof.ui.settingsPage
import com.driveproof.ui.tripList
import com.driveproof.ui.scenesPage
import com.driveproof.ui.tripCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private val database by lazy { DriveDatabase.get(this) }
    private val repository by lazy { TripRepository(database, BuildConfig.IS_DEMO) }
    private var proof: ProofSnapshot? = null
    private var historyDetailJob: Job? = null
    private var historyDetailKey: String? = null
    private var recentScenes = emptyList<SceneEntity>()
    private var exemptEvents = emptyList<ExemptEventEntity>()
    private var records = emptyList<TripEntity>()
    private var preparing = false
    private var observer: Job? = null
    private var visible = false
    private var scenesJob: Job? = null
    private var sceneVersion: Pair<Long, Long?>? = null
    private var sceneRows = emptyList<SceneEntity>()
    private var collapseWideScenes = false
    private val consentStore by lazy { ConsentStore(this) }
    private var consent: RecordingConsent? = null
    private var consentJob: Job? = null

    private data class Destination(val page: String, val tripId: Long? = null, val argument: String? = null)
    private val stack = ArrayList<Destination>()
    private var autoOpen = true
    private val launchedCards = mutableSetOf<Pair<Long, Long>>()
    private val speedGranted get() = checkSelfPermission(CAR_SPEED) == PackageManager.PERMISSION_GRANTED
    private var permissionInProgress = false
    private val locationAvailable by lazy { FusedHeadingSource.isAvailable(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) root.post(::render)
        }
        if (savedInstanceState != null) {
            collapseWideScenes = savedInstanceState.getBoolean("collapseWideScenes")
            autoOpen = savedInstanceState.getBoolean("autoOpen")
            permissionInProgress = savedInstanceState.getBoolean("permissionInProgress")
            repeat(savedInstanceState.getInt("stackSize")) { i ->
                stack += Destination(savedInstanceState.getString("page$i") ?: "home",
                    savedInstanceState.getLong("trip$i", -1).takeIf { it > 0 },
                    savedInstanceState.getString("argument$i"))
            }
        }
        if (stack.isEmpty()) enter(intent)
        render()
        // 제출 APK만 첫 실행 때 시연 기록을 넣는다. 넣고 나면 Room 관찰이 화면을 다시 그린다
        CoroutineScope(Dispatchers.Main).launch {
            runCatching { firstRunSeed(applicationContext) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("autoOpen", autoOpen)
        outState.putBoolean("collapseWideScenes", collapseWideScenes)
        outState.putBoolean("permissionInProgress", permissionInProgress)
        outState.putInt("stackSize", stack.size)
        stack.forEachIndexed { i, route ->
            outState.putString("page$i", route.page)
            outState.putLong("trip$i", route.tripId ?: -1)
            outState.putString("argument$i", route.argument)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        observer = CoroutineScope(Dispatchers.Main).launch {
            database.trips().observe(DEFAULT_PROFILE, BuildConfig.IS_DEMO)
                .combine(TripService.preparingCard) { trips, pending -> trips.filter { it.status == "ENDED" } to (pending > 0) }
                .distinctUntilChanged()
                .collect { (trips, pending) ->
                    records = trips
                    preparing = pending
                    openUnseen()
                    render()
                    if (!pending) {
                        proof = withContext(Dispatchers.IO) { repository.proofSnapshot(System.currentTimeMillis()) }
                        if (stack.lastOrNull()?.page in setOf("home", "report", "logs", "principle", "reasons")) render()
                    }
                }
        }
    }

    override fun onResume() {
        super.onResume()
        consentJob?.cancel()
        consentJob = CoroutineScope(Dispatchers.Main).launch {
            val value = consentStore.values.first()
            consent = value
            if (value.accepted) {
                if (!getPreferences(MODE_PRIVATE).getBoolean("askedSpeed", false) && !permissionInProgress) {
                    requestRecordPermissions()
                }
                if (speedGranted) startRecording()
            }
            render()
        }
    }

    override fun onStop() {
        visible = false
        observer?.cancel()
        scenesJob?.cancel()
        historyDetailJob?.cancel()
        historyDetailKey = null
        consentJob?.cancel()
        sceneVersion = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enter(intent)
        openUnseen()
        render()
    }

    private fun enter(intent: Intent) {
        stack.clear()
        collapseWideScenes = false
        val id = intent.getLongExtra(TripNotifications.EXTRA_TRIP_ID, -1).takeIf { it > 0 }
        autoOpen = id == null
        stack += if (id == null) Destination("home") else Destination("trip", id)
    }

    private fun openUnseen() {
        if (!autoOpen || stack.lastOrNull()?.page != "home") return
        val latest = records.firstOrNull { it.seenAt == null && it.cardJson != null } ?: return
        autoOpen = false
        stack += Destination("trip", latest.id, "unseen")
    }

    private fun navigate(page: String, tripId: Long? = null, argument: String? = null) {
        autoOpen = false
        collapseWideScenes = false
        stack += Destination(page, tripId, argument)
        render()
    }

    private fun goBack() {
        if (stack.size <= 1) finish() else {
            autoOpen = false
            stack.removeAt(stack.lastIndex)
            render()
        }
    }

    private fun selectTab(argument: String) {
        val current = stack.lastOrNull() ?: return
        stack[stack.lastIndex] = current.copy(argument = argument)
        render()
    }

    /** [닫기]는 지금 화면만 닫는다. 맨 위 화면에서만 앱을 닫는다 (overview.md "화면 이동") */
    private fun closePage() = goBack()

    @Deprecated("Legacy back dispatch matches the current Activity target.")
    override fun onBackPressed() = goBack()

    private fun render() {
        if (!::root.isInitialized || isFinishing) return
        val density = resources.displayMetrics.density
        val width = if (root.width > 0) (root.width / density).roundToInt() else resources.configuration.screenWidthDp
        val height = if (root.height > 0) (root.height / density).roundToInt() else resources.configuration.screenHeightDp
        val ui = Ui(this, width, height)
        // 가로 화면에서는 좁은 창에서 열었던 장면도 한 장의 오른쪽 칸으로 표시한다.
        if (ui.wide && !preparing && stack.lastOrNull()?.page == "scenes") stack.removeAt(stack.lastIndex)
        val route = stack.lastOrNull() ?: Destination("home")
        val trip = route.tripId?.let { id -> records.find { it.id == id } }
        val back = if (stack.size > 1 && route.argument != "unseen") ::goBack else null
        val showScenes = trip != null && (route.page == "scenes" || (ui.wide && !collapseWideScenes && !preparing))
        if (showScenes && visible) loadScenes(trip)
        val value = consent
        val speedRequired = !speedGranted && !getPreferences(MODE_PRIVATE).getBoolean("speedGrantedBefore", false)
        val view = if (value == null) {
            ui.tripCard(null, false, null, ::closePage)
        } else if (!value.accepted) {
            ui.consentPage(value, ::closePage, locationAvailable, ::acceptConsent)
        } else if (speedRequired) {
            ui.page(getString(R.string.app_name), back, ::closePage, false) {
                with(ui) {
                    add(text(string(com.driveproof.ui.R.string.speed_permission_required), 32, bold = true))
                    space(24)
                    add(action(string(com.driveproof.ui.R.string.speed_permission_retry), true, ::retrySpeed))
                }
            }
        } else if (route.page == "home" && !preparing) {
            ui.proofPage(proof, records, speedGranted, ::retrySpeed, ::closePage,
                openTrip = { navigate("trip", it) },
                settings = { navigate("settings") },
                openPrinciple = { navigate("principle", argument = it.name) },
                actions = buildList {
                    add(ui.string(com.driveproof.ui.R.string.reason_title) to { navigate("reasons") })
                    add(ui.string(com.driveproof.ui.R.string.history_weekly) to { navigate("logs") })
                    add(ui.string(com.driveproof.ui.R.string.monthly_title) to { navigate("report") })
                    add(ui.string(com.driveproof.ui.R.string.rules_title) to { navigate("rules") })
                    if (!ui.wide) add(ui.string(com.driveproof.ui.R.string.history_title) to { navigate("history") })
                    ui.insurerEntry { navigate("insurer") }?.let { add(it) }
                })
        } else if (route.page == "home") {
            ui.tripCard(null, true, null, ::closePage)
        } else if (route.page == "history") {
            ui.tripList(records, ::goBack, ::closePage) { navigate("trip", it) }
        } else if (route.page == "settings") {
            ui.settingsPage(value, BuildConfig.IS_DEMO, ::goBack, ::closePage, ::saveSettings,
                { navigate("history") }, ::clearRecords, locationAvailable,
                if (value.location && locationAvailable && !FusedHeadingSource.hasPermissions(this)) ::locationSettings else null)
        } else if (route.page == "report") {
            ui.monthlyReport(proof, ::goBack, ::closePage)
        } else if (route.page == "rules") {
            ui.scoringRules(::goBack, ::closePage)
        } else if (route.page == "insurer") {
            ui.insurerPreview(::goBack, ::closePage) ?: ui.tripCard(null, false, back, ::closePage)
        } else if (route.page == "principle") {
            val principle = Principle.valueOf(requireNotNull(route.argument))
            loadHistoryDetail(principle.name)
            ui.principleDetail(proof, principle, recentScenes, ::goBack, ::closePage)
        } else if (route.page == "logs") {
            ui.historyLog(proof, route.argument == "month", ::goBack, ::closePage) { month ->
                selectTab(if (month) "month" else "week")
            }
        } else if (route.page == "reasons") {
            if (route.argument == "excluded") loadHistoryDetail("excluded")
            ui.reasonLog(proof, records, exemptEvents, route.argument == "excluded", ::goBack, ::closePage) { excluded ->
                selectTab(if (excluded) "excluded" else "fix")
            }
        } else if (showScenes) {
            val closeScenes = {
                if (ui.wide) collapseWideScenes = true
                if (route.page == "scenes") goBack() else render()
            }
            ui.scenesPage(
                trip, if (sceneVersion?.first == trip.id) sceneRows else emptyList(),
                back = closeScenes, close = closeScenes, tripBack = back, tripClose = ::closePage,
            )
        } else ui.tripCard(trip, preparing && route.page == "home", back, ::closePage, trip?.let {
            {
                if (ui.wide && !preparing) {
                    collapseWideScenes = false
                    render()
                } else navigate("scenes", it.id)
            }
        })
        root.removeAllViews()
        root.addView(view)
        if (visible && value?.accepted == true && !speedRequired && !preparing && trip?.cardJson != null) {
            val end = trip.endAt
            if (end != null && trip.seenAt == null && launchedCards.add(trip.id to end)) {
                CoroutineScope(Dispatchers.IO).launch { database.trips().markSeen(trip.id, System.currentTimeMillis(), end) }
            }
        }
    }

    private fun loadHistoryDetail(key: String) {
        if (!visible || historyDetailKey == key) return
        historyDetailKey = key
        historyDetailJob?.cancel()
        recentScenes = emptyList()
        exemptEvents = emptyList()
        historyDetailJob = CoroutineScope(Dispatchers.Main).launch {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            if (key == "excluded") {
                exemptEvents = withContext(Dispatchers.IO) {
                    listOf("TELLTALE", "SCENE").flatMap {
                        database.exempts().since(DEFAULT_PROFILE, BuildConfig.IS_DEMO, now - 183L * 86_400_000L, it)
                    }
                }
            } else {
                val from = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusMonths(7).atStartOfDay(zone).toInstant().toEpochMilli()
                recentScenes = withContext(Dispatchers.IO) {
                    database.scenes().since(DEFAULT_PROFILE, BuildConfig.IS_DEMO, from, -1)
                        .filter { it.principle == key }.sortedByDescending { it.at }.take(10)
                }
            }
            render()
        }
    }

    private fun saveSettings(value: RecordingConsent) {
        val enablingLocation = value.location && consent?.location != true
        consent = value
        CoroutineScope(Dispatchers.Main).launch {
            consentStore.save(value)
            if (enablingLocation) requestLocation()
            render()
        }
    }

    private fun clearRecords(onlyDemo: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) { repository.clear(onlyDemo) }
            records = emptyList()
            proof = null
            sceneVersion = null
            sceneRows = emptyList()
            historyDetailJob?.cancel()
            historyDetailKey = null
            recentScenes = emptyList()
            exemptEvents = emptyList()
            launchedCards.clear()
            render()
        }
    }

    private fun acceptConsent(value: RecordingConsent) {
        consentJob?.cancel()
        consentJob = CoroutineScope(Dispatchers.Main).launch {
            consentStore.save(value)
            consent = value
            requestRecordPermissions()
            if (speedGranted) startRecording()
            render()
        }
    }

    private suspend fun startRecording() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("speedGrantedBefore", true).apply()
        TripService.ensureRunning(this)
    }

    private fun loadScenes(trip: TripEntity) {
        val key = trip.id to trip.endAt
        if (sceneVersion == key) return
        sceneVersion = key
        sceneRows = emptyList()
        scenesJob?.cancel()
        scenesJob = CoroutineScope(Dispatchers.Main).launch {
            sceneRows = withContext(Dispatchers.IO) { database.scenes().byTrip(trip.id) }
            render()
        }
    }

    private fun retrySpeed() {
        if (!speedGranted && getPreferences(MODE_PRIVATE).getBoolean("askedSpeed", false) &&
            !shouldShowRequestPermissionRationale(CAR_SPEED)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else requestRecordPermissions()
    }

    private fun requestRecordPermissions() {
        if (permissionInProgress) return
        getPreferences(MODE_PRIVATE).edit().putBoolean("askedSpeed", true).apply()
        if (!speedGranted) {
            permissionInProgress = true
            requestPermissions(arrayOf(CAR_SPEED), SPEED_REQUEST)
        } else requestNotifications()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("askedNotifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply()
            permissionInProgress = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        } else requestLocation()
    }

    private fun requestLocation() {
        if (permissionInProgress || !locationAvailable || consent?.location != true) return
        if (checkSelfPermission(FusedHeadingSource.PLEOS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            permissionInProgress = true
            requestPermissions(arrayOf(FusedHeadingSource.PLEOS_PERMISSION), PLEOS_LOCATION_REQUEST)
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionInProgress = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST)
        } else requestBackgroundLocation()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT == 29 && !FusedHeadingSource.hasPermissions(this)) {
            permissionInProgress = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), BACKGROUND_LOCATION_REQUEST)
        }
    }

    private fun locationSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionInProgress = false
        if (requestCode == SPEED_REQUEST && speedGranted) {
            CoroutineScope(Dispatchers.Main).launch { startRecording() }
            requestNotifications()
        }
        if (requestCode == NOTIFICATION_REQUEST) requestLocation()
        if (requestCode == PLEOS_LOCATION_REQUEST &&
            checkSelfPermission(FusedHeadingSource.PLEOS_PERMISSION) == PackageManager.PERMISSION_GRANTED) requestLocation()
        if (requestCode == LOCATION_REQUEST &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) requestBackgroundLocation()
        if ((requestCode == LOCATION_REQUEST || requestCode == BACKGROUND_LOCATION_REQUEST) && speedGranted) {
            CoroutineScope(Dispatchers.Main).launch { startRecording() }
        }
        render()
    }

    private companion object {
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
        const val SPEED_REQUEST = 10
        const val NOTIFICATION_REQUEST = 11
        const val LOCATION_REQUEST = 12
        const val BACKGROUND_LOCATION_REQUEST = 13
        const val PLEOS_LOCATION_REQUEST = 14
    }
}
