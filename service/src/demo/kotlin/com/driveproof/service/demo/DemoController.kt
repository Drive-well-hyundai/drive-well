package com.driveproof.service.demo

import android.content.Context
import com.driveproof.model.Signal
import com.driveproof.data.DriveDatabase
import com.driveproof.data.TripRepository
import com.driveproof.model.Speed
import com.driveproof.model.Tbt
import com.driveproof.model.playScenario
import com.driveproof.model.TurnSignal
import com.driveproof.service.BuildConfig
import com.driveproof.service.TripService
import com.driveproof.source.DemoLog
import com.driveproof.source.SignalSink
import com.driveproof.source.SignalThread
import com.driveproof.source.SourceKind
import com.driveproof.source.VehicleDataSource
import com.driveproof.source.hybrid.HybridSource
import com.driveproof.source.live.LiveSource
import com.driveproof.source.replay.ReplaySource
import com.driveproof.source.replay.ScenarioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 시연 진입 명령(`dp.DEMO`)을 받아 신호 출처를 바꾼다. demo 빌드에만 있다.
 * 모드 목록은 implementation.md "시연 진입"이다.
 *
 * 모든 상태는 dp-signal 스레드에서만 읽고 쓴다.
 * TripService가 [attach]로 신호를 받아 간다. 서비스가 붙기 전에 온 명령은 붙을 때 실행한다.
 */
object DemoController {

    /** 재생기. `:model`의 10Hz 재생기(E-1)다 */
    @Volatile var player: ScenarioPlayer? = ScenarioPlayer(::playScenario)

    /** 시연 주행 표시. demo 빌드의 주행은 live 모드를 포함해 모두 시연이다(implementation.md "저장 스키마") */
    val isDemo: Boolean get() = BuildConfig.IS_DEMO

    @Volatile private var current: VehicleDataSource? = null
    private var lastLoggedKmh: Int? = null

    /** TripService의 입구. 붙기 전에는 null이다 */
    private var out: SignalSink? = null
    private val pending = ArrayList<Pair<String?, Map<String, String?>>>()

    /** 지금 신호를 내는 출처 */
    val currentKind: SourceKind? get() = current?.kind

    /** 시연 진입 명령. 서비스를 먼저 띄우고, 서비스가 붙으면 실행한다 */
    suspend fun handle(context: Context, mode: String?, extras: Map<String, String?>) {
        val app = context.applicationContext
        val synthetic = mode in setOf("replay", "seed", "clear")
        if (!TripService.ensureRunning(app, synthetic)) return
        SignalThread.run {
            if (out == null) pending += mode to extras else dispatch(app, mode, extras)
        }
    }

    /**
     * 제출 APK의 첫 실행 때 6개월 시연 기록(m6)을 오늘 기준으로 넣는다(결정 36). 입출력 스레드에서 부른다.
     * 넣은 주행 수를 돌려준다. 기록은 모두 시연(isDemo)이다.
     */
    fun importFirstRunSeed(context: Context): Int = importSeed(context.applicationContext, "m6", null)

    /** TripService가 시작될 때 부른다. 밀린 명령을 실행하고, 없으면 live로 시작한다 */
    fun attach(context: Context, sink: SignalSink, liveAllowed: Boolean) {
        val app = context.applicationContext
        SignalThread.run {
            out = sink
            val queued = pending.toList()
            pending.clear()
            queued.forEach { (mode, extras) -> dispatch(app, mode, extras) }
            if (current == null && liveAllowed) live(app)
        }
    }

    /** TripService가 끝날 때 부른다 */
    fun detach() {
        SignalThread.run {
            stopCurrent()
            out = null
        }
    }

    private fun dispatch(context: Context, mode: String?, extras: Map<String, String?>) {
        DemoLog.info("dp.DEMO mode=$mode ${extras.filterValues { it != null }}")
        when (mode) {
            "replay" -> replay(context, extras["scenario"])
            "live" -> live(context)
            "seed" -> CoroutineScope(Dispatchers.IO).launch {
                runCatching { importSeed(context, extras["scenario"], extras["now"]) }
                    .onSuccess { DemoLog.info("seed: ${it}건 저장 완료") }
                    .onFailure { DemoLog.warn("seed: 저장 실패: ${it.message}") }
            }.let { }
            "clear" -> CoroutineScope(Dispatchers.IO).launch {
                runCatching { TripRepository(DriveDatabase.get(context), demo = true).clear(onlyDemo = true) }
                    .onSuccess { DemoLog.info("clear: 시연 기록을 지웠어요") }
                    .onFailure { DemoLog.warn("clear: ${it.message}") }
            }.let { }
            "hybrid" -> hybrid(context, extras["dest"])
            else -> DemoLog.warn("모르는 모드: $mode (hybrid, replay, seed, live, clear)")
        }
    }

    private fun replay(context: Context, scenario: String?) {
        if (scenario.isNullOrBlank()) {
            DemoLog.warn("replay: --es scenario 가 없다 (예: s1)")
            return
        }
        val json = try {
            context.assets.open("$scenario.json").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            DemoLog.warn("replay: 에셋 $scenario.json 이 없다")
            return
        }
        val p = player
        if (p == null) {
            DemoLog.warn("replay: 재생기가 없다. $scenario.json ${json.length}자를 읽었다")
            return
        }
        val signals = try {
            p.play(json)
        } catch (e: RuntimeException) {
            DemoLog.warn("replay: $scenario.json 을 읽지 못했다: ${e.message}")
            return
        }
        stopCurrent()
        val source = ReplaySource(signals, onFinished = {
            DemoLog.info("replay: $scenario 끝")
            current = null
        })
        current = source
        lastLoggedKmh = null
        DemoLog.info("replay: $scenario 시작 (신호 ${signals.size}개, ${source.durationMs / 1000.0}초)")
        source.start(::onSignal)
    }

    private fun live(context: Context) {
        stopCurrent()
        val source = LiveSource(context)
        current = source
        lastLoggedKmh = null
        DemoLog.info("live: 실제 소스로 돌아간다")
        source.start(::onSignal)
    }

    private fun hybrid(context: Context, destText: String?) {
        val dest = if (destText == null) HybridSource.Dest.DEFAULT else HybridSource.Dest.parse(destText) ?: run {
            DemoLog.warn("hybrid: --es dest 는 \"위도,경도\"다. 받은 값: $destText")
            return
        }
        stopCurrent()
        val source = HybridSource(context, dest)
        current = source
        lastLoggedKmh = null
        source.start(::onSignal)
    }

    private fun stopCurrent() {
        current?.let {
            it.stop()
            DemoLog.info("${it.kind} 소스를 멈췄다")
        }
        current = null
    }

    private fun onSignal(s: Signal) {
        // 속도는 10Hz로 온다. 1km/h 단위로 바뀔 때만 남긴다
        val log = if (s is Speed) {
            val kmh = s.kmh.roundToInt()
            (kmh != lastLoggedKmh).also { lastLoggedKmh = kmh }
        } else {
            true
        }
        val src = current
        when {
            !log -> Unit
            // 하이브리드의 합성 TBT는 HybridSource가 [계산]으로 남긴다
            src is HybridSource && s is Tbt -> Unit
            // 깜빡이를 켠 순간의 남은 거리를 한 줄에 (demo-replay.md "구조")
            src is HybridSource && s is TurnSignal ->
                DemoLog.sdk(DemoLog.describe(s) + (src.remainingM?.let { " · [계산] 남은 거리 ${it.roundToInt()}m" } ?: ""))
            src?.kind == SourceKind.REPLAY -> DemoLog.replay(DemoLog.describe(s))
            else -> DemoLog.sdk(DemoLog.describe(s))
        }
        out?.onSignal(s)
    }
}
