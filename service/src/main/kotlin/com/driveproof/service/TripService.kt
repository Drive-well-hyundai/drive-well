package com.driveproof.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.driveproof.model.Heading
import com.driveproof.model.Signal
import com.driveproof.service.trip.EngineCardMaker
import com.driveproof.service.trip.EngineHost
import com.driveproof.data.DriveDatabase
import com.driveproof.data.ConsentStore
import com.driveproof.data.RecordingConsent
import com.driveproof.data.TripRepository
import com.driveproof.data.refreshWeeks
import com.driveproof.service.trip.TripEvent
import com.driveproof.service.trip.TripState
import com.driveproof.service.trip.TripStateMachine
import com.driveproof.service.trip.TripStore
import com.driveproof.source.DemoLog
import com.driveproof.source.SignalSink
import com.driveproof.source.SignalThread
import com.driveproof.source.SourceKind
import com.driveproof.source.live.FusedHeadingSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 주행 기록 포그라운드 서비스. 부팅 때 띄워 끝까지 상주한다(driving-record.md "백그라운드 실행").
 * Android 12 이상에서는 주행이 시작될 때 서비스를 새로 띄울 수 없다.
 *
 * 상태(대기, 기록 중, 이어 붙이기 대기)는 [TripStateMachine]이 가른다. 신호와 상태는 dp-signal 스레드에서만 다룬다.
 * 저장은 입출력 스레드("dp-io") 하나에서 순서대로 한다. 서비스는 화면에 값을 보내지 않는다.
 *
 * 비정상 종료 뒤 다시 뜨면 OPEN인 주행을 찾아 이어서 기록한다(implementation.md "오류 처리").
 * 그래서 신호 출처는 복구를 마친 뒤에 켠다. 기록 중에는 거리를 10초마다 중간 저장한다.
 */
class TripService : Service() {

    private val expireToken = Any()
    private val checkpointToken = Any()
    private lateinit var store: TripStore
    private lateinit var maker: EngineCardMaker
    private lateinit var repo: TripRepository
    private lateinit var io: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val headingSource by lazy { FusedHeadingSource(applicationContext) }
    @Volatile private var consent = RecordingConsent()
    private var tripConsent = RecordingConsent()
    private var consentReady = false
    private var storageReady = false
    private var sourcesAttached = false
    private var sourcesLive = false

    // dp-signal 스레드에서만 쓴다
    private var machine: TripStateMachine? = null
    private var tripId: Future<Long>? = null
    private var clockOffset = 0L
    private var lastSignalAt: Long? = null
    private var lastSource: String? = null

    override fun onCreate() {
        super.onCreate()
        TripNotifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            TripNotifications.NOTIFICATION_ID,
            TripNotifications.build(this, recording = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        io = Executors.newSingleThreadExecutor { Thread(it, "dp-io") }
        repo = TripRepository(DriveDatabase.get(this), BuildConfig.IS_DEMO)
        store = TripStore(repo)
        maker = EngineCardMaker(this, repo)
        scope.launch {
            ConsentStore(this@TripService).values.collect {
                consent = it
                consentReady = true
                connectSources()
            }
        }
        DemoLog.info("TripService 시작 (isDemo=${BuildConfig.IS_DEMO}, MIN_KM=${BuildConfig.MIN_KM})")
        io.execute {
            repo.trim(System.currentTimeMillis())
            repo.refreshWeeks(System.currentTimeMillis())
            val open = store.findOpen()
            SignalThread.run {
                tripConsent = consent
                EngineHost.reset(tripConsent.controls)
                val m = TripStateMachine(BuildConfig.MIN_KM)
                machine = m
                if (open != null) {
                    clockOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                    m.restore(open.startAt - clockOffset, open.km, open.limitKnownKm)
                    tripId = CompletableFuture.completedFuture(open.id)
                    DemoLog.calc("비정상 종료 뒤 이어서 기록: Trip ${open.id}, 저장된 거리 ${km(open.km)}km. 기어가 P면 이 주행을 끝낸다")
                    TripNotifications.update(this, recording = true)
                    scheduleCheckpoint()
                }
            }
            main.post {
                storageReady = true
                connectSources()
            }
        }
    }

    /** 시스템이 서비스를 정리하면 다시 띄운다. 다시 산 시점부터 이어서 기록한다 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectSources()
        return START_STICKY
    }

    private fun connectSources() {
        if (!storageReady || !consentReady || !scope.isActive) return
        val live = consent.accepted && hasSpeed(this)
        if (!BuildConfig.IS_DEMO && !live) {
            stopSelf()
            return
        }
        headingSource.update(live && consent.controls && consent.location, sink)
        if (sourcesAttached && live == sourcesLive) return
        if (sourcesAttached && !live) buildVariant.stopSources()
        if (!sourcesAttached) SignalThread.run {
            tripConsent = consent
            EngineHost.reset(tripConsent.controls)
        }
        sourcesAttached = true
        sourcesLive = live
        buildVariant.startSources(this, sink, live)
    }

    override fun onDestroy() {
        scope.cancel()
        headingSource.stop(sink)
        DemoLog.info("TripService 끝")
        buildVariant.stopSources()
        SignalThread.run {
            SignalThread.handler.removeCallbacksAndMessages(expireToken)
            SignalThread.handler.removeCallbacksAndMessages(checkpointToken)
            machine = null
        }
        io.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val sink = SignalSink { s -> onSignal(s) }

    private fun onSignal(s: Signal) {
        var m = machine ?: return
        if (s is Heading && (buildVariant.sourceKind() == SourceKind.REPLAY ||
                (s.deg != null && (!consent.location || !consent.controls)))) return
        val source = buildVariant.sourceKind()?.name ?: "LIVE"
        if (lastSignalAt?.let { s.t < it && source == "REPLAY" } == true ||
            (lastSource != null && lastSource != source && m.state != TripState.RECORDING)) {
            m.expire().forEach(::onEvent)
            tripConsent = consent
            EngineHost.reset(tripConsent.controls)
            m = TripStateMachine(BuildConfig.MIN_KM)
            machine = m
        }
        lastSignalAt = s.t
        lastSource = source
        if (m.state == TripState.IDLE && tripConsent != consent) {
            tripConsent = consent
            EngineHost.reset(tripConsent.controls)
        }
        if (!tripConsent.allows(s)) return
        val events = m.onSignal(s)
        // 종료 신호가 엔진에도 전달된 뒤 결과를 확정한다.
        engine?.onSignal(s)
        events.forEach(::onEvent)
        if (m.state == TripState.RECORDING) {
            val confirmed = EngineHost.engine.confirmed()
            val id = tripId
            val offset = clockOffset
            if (confirmed.isNotEmpty()) io.execute {
                id?.get()?.let { store.append(it, confirmed) { t -> t + offset } }
            }
        }
    }

    private fun onEvent(e: TripEvent) {
        when (e) {
            is TripEvent.Started -> {
                val source = buildVariant.sourceKind()?.name ?: "LIVE"
                clockOffset = System.currentTimeMillis() -
                    if (source == "REPLAY") e.t else SystemClock.elapsedRealtime()
                val at = e.t + clockOffset
                val odoStart = machine?.odoStart
                tripId = io.submit<Long> { store.insertOpen(at, source, odoStart) }
                DemoLog.calc("주행 시작 t=${sec(e.t)}s ($source)")
                TripNotifications.update(this, recording = true)
                scheduleCheckpoint()
            }
            is TripEvent.Parked -> {
                // 주행 끝 순서(implementation.md): 남은 장면 확정과 한 장 → 저장 → MIN_KM 이상이면 주차 알림
                val result = EngineHost.engine.end(e.t)
                val offset = clockOffset
                val id = tripId
                if (!e.short) pendingParks.update { it + 1 }
                io.execute {
                    try {
                        val tid = id?.get() ?: return@execute
                        val card = store.end(tid, e.t + offset, e, result, offset, maker)
                        repo.refreshWeeks(System.currentTimeMillis())
                        if (!e.short) {
                            TripNotifications.showParked(this, tid, card?.firstLine)
                            DemoLog.calc("주차 알림 tripId=$tid ${card?.firstLine.orEmpty()}")
                        }
                    } finally {
                        if (!e.short) pendingParks.update { it - 1 }
                    }
                }
                val gap = if (e.hadGap) ", 기록이 끊긴 구간 있음" else ""
                DemoLog.calc("기어 P: 주행 ${if (e.short) "SHORT" else "ENDED"} ${km(e.km)}km (${e.kmSource})$gap. 30초 안에 출발하면 잇는다")
                // 재생처럼 신호가 끊기면 신호 시각으로는 30초를 못 잰다. 실제 시간으로도 잰다
                SignalThread.handler.postAtTime(
                    { machine?.expire()?.forEach(::onEvent) },
                    expireToken,
                    SystemClock.uptimeMillis() + RESUME_WINDOW_MS,
                )
            }
            is TripEvent.Resumed -> {
                SignalThread.handler.removeCallbacksAndMessages(expireToken)
                val id = tripId
                io.execute {
                    id?.get()?.let { store.reopen(it) }
                    TripNotifications.cancelParked(this)
                }
                DemoLog.calc("30초 안에 다시 출발: 같은 주행을 잇는다")
            }
            is TripEvent.Ended -> {
                SignalThread.handler.removeCallbacksAndMessages(expireToken)
                SignalThread.handler.removeCallbacksAndMessages(checkpointToken)
                tripId = null
                DemoLog.calc("이어 붙이기 끝: 주행 ${km(e.km)}km 확정. 대기로")
                TripNotifications.update(this, recording = false)
            }
        }
    }

    /** 기록 중 거리를 10초마다 저장한다. 비정상 종료 뒤 이어 쓸 거리다. dp-signal 스레드에서 부른다 */
    private fun scheduleCheckpoint() {
        SignalThread.handler.removeCallbacksAndMessages(checkpointToken)
        SignalThread.handler.postAtTime({
            val m = machine ?: return@postAtTime
            if (m.state == TripState.IDLE) return@postAtTime
            val id = tripId
            val kmNow = m.km
            val odoStart = m.odoStart
            val limitKnownKm = m.limitKnownKm
            io.execute { id?.get()?.let { store.checkpoint(it, kmNow, odoStart, limitKnownKm) } }
            scheduleCheckpoint()
        }, checkpointToken, SystemClock.uptimeMillis() + CHECKPOINT_MS)
    }

    private fun sec(t: Long) = String.format(Locale.ROOT, "%.1f", t / 1000.0)
    private fun km(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    companion object {
        private const val RESUME_WINDOW_MS = 30_000L
        private val pendingParks = MutableStateFlow(0)
        val preparingCard = pendingParks.asStateFlow()
        private const val CHECKPOINT_MS = 10_000L

        /** 엔진 입구. 신호는 dp-signal에서 온다 */
        @Volatile var engine: SignalSink? = EngineHost

        const val CAR_SPEED = "android.car.permission.CAR_SPEED"

        private fun hasSpeed(context: Context) = context.checkSelfPermission(CAR_SPEED) == PackageManager.PERMISSION_GRANTED

        /** 동의와 속도 권한이 필요하다. demo의 합성 기록만 예외다. */
        suspend fun ensureRunning(context: Context, synthetic: Boolean = false): Boolean {
            if (!(BuildConfig.IS_DEMO && synthetic) && (!hasSpeed(context) || !ConsentStore(context).values.first().accepted)) return false
            ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
            return true
        }
    }
}
