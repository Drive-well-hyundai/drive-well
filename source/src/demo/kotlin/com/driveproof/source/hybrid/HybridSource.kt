package com.driveproof.source.hybrid

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TBTInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.driveproof.model.Signal
import com.driveproof.model.Speed
import com.driveproof.model.Tbt
import com.driveproof.model.TurnType
import com.driveproof.source.DemoLog
import com.driveproof.source.SignalSink
import com.driveproof.source.SignalThread
import com.driveproof.source.SourceKind
import com.driveproof.source.VehicleDataSource
import com.driveproof.source.live.LiveSource
import java.util.Locale

/**
 * 에뮬레이터 시연용 하이브리드(demo-replay.md "구조", implementation.md "시연 진입" `hybrid`).
 *
 * - 차량 신호(속도, 기어, 깜빡이, 브레이크, 조향)와 회전 목록은 LiveSource로 SDK에서 받는다.
 * - 회전 목록은 처음 받은 것을 한 번 고정한다. 그 뒤 오는 실제 `onTBTInfo`는 엔진에 넘기지 않는다.
 * - 남은 거리 = 항목 `distance` − ∫(라이브 속도)dt를 0.1초마다 합성 `Tbt`로 낸다. 에뮬레이터 위치가 움직이지 않아서다.
 *
 * 목록 받기: `getTBTInfo()`를 1초마다 부른다. 미리 걸어 둔 경로가 있으면 그것을 쓴다.
 * 150초 동안 빈 목록이면 [dest]로 `requestRoute`를 한 번 부른다.
 */
class HybridSource(context: Context, private val dest: Dest = Dest.DEFAULT) : VehicleDataSource {

    /** 고정 목적지. 조건은 implementation.md "고정 목적지": 첫 항목 좌·우회전 70m 이상, 둘째 250m 이상 */
    data class Dest(val lat: Double, val lng: Double, val name: String) {
        companion object {
            val DEFAULT = Dest(37.3947, 127.1112, "판교역")

            /** `--es dest "위도,경도"` */
            fun parse(text: String?): Dest? {
                val parts = text?.split(",")?.map { it.trim().toDoubleOrNull() } ?: return null
                if (parts.size != 2 || parts.any { it == null }) return null
                return Dest(parts[0]!!, parts[1]!!, "지정 목적지")
            }
        }
    }

    override val kind = SourceKind.HYBRID

    private val live = LiveSource(context).also { it.emitTbt = false }
    private val main = Handler(Looper.getMainLooper())
    private val token = Any()

    // dp-signal 스레드에서만 쓴다
    private var sink: SignalSink? = null
    private var track: TurnTrack? = null
    private var speedKmh = 0.0
    private var lastTickAt = 0L

    // 메인 스레드에서만 쓴다
    private var waitStartedAt = 0L
    private var routeRequested = false
    private var lastWaitLogSec = -1L

    @Volatile private var frozen = false

    /** 지금 회전까지 남은 거리(m). 목록을 받기 전이거나 다 지났으면 null. dp-signal 스레드에서 읽는다 */
    val remainingM: Double? get() = track?.takeIf { !it.finished }?.remainingM

    override fun start(sink: SignalSink) {
        SignalThread.run {
            this.sink = sink
            track = null
            speedKmh = 0.0
        }
        frozen = false
        live.onTbtList = ::onTurnList
        live.start(::onLiveSignal)
        main.post {
            waitStartedAt = SystemClock.uptimeMillis()
            routeRequested = false
            lastWaitLogSec = -1
            poll()
        }
        DemoLog.info("hybrid: 회전 목록을 기다린다. ${WAIT_MS / 1000}초 동안 없으면 ${dest.name}(${dest.lat}, ${dest.lng})로 경로를 요청한다")
    }

    override fun stop() {
        live.onTbtList = null
        live.stop()
        main.post { main.removeCallbacksAndMessages(token) }
        SignalThread.run {
            SignalThread.handler.removeCallbacksAndMessages(token)
            sink = null
            track = null
        }
    }

    // ---------- 회전 목록 받기 (메인 스레드) ----------

    private fun poll() {
        if (frozen) return
        val elapsed = SystemClock.uptimeMillis() - waitStartedAt
        if (!routeRequested && elapsed >= WAIT_MS) {
            routeRequested = true
            live.withNavi {
                it.requestRoute(RouteInfo(dest.lat, dest.lng, null, null, dest.name, "", "", "", RouteOption.RECOMMENDED, "", false))
            }
            DemoLog.sdk("경로 요청: ${dest.name}(${dest.lat}, ${dest.lng})")
        }
        live.withNavi { it.getTBTInfo() }
        val sec = elapsed / 1000
        if (sec % 10 == 0L && sec != lastWaitLogSec) {
            lastWaitLogSec = sec
            DemoLog.info("hybrid: 경로 대기 ${sec}초")
        }
        main.postAtTime(::poll, token, SystemClock.uptimeMillis() + POLL_MS)
    }

    /** 콜백 스레드는 확인 불가다. 처음 받은 빈 목록이 아닌 목록을 고정한다 */
    private fun onTurnList(list: List<TBTInfo>) {
        if (frozen || list.isEmpty()) return
        frozen = true
        val turns = list.map { Turn(TurnType.fromName(it.type.name), it.distance) }
        val first = list.first()
        SignalThread.run {
            DemoLog.sdk("회전 목록 ${turns.size}개 고정: 첫 회전 ${first.type} ${m(first.distance)}m '${first.description}'")
            track = TurnTrack(turns)
            lastTickAt = SystemClock.elapsedRealtime()
            tick()
        }
    }

    // ---------- 남은 거리 (dp-signal 스레드) ----------

    private fun onLiveSignal(s: Signal) {
        if (s is Speed) speedKmh = s.kmh
        sink?.onSignal(s)
    }

    private fun tick() {
        val tr = track ?: return
        val now = SystemClock.elapsedRealtime()
        val passed = tr.advance(now - lastTickAt, speedKmh)
        lastTickAt = now
        for (turn in passed) {
            sink?.onSignal(Tbt(now, turn.type, 0.0))
            DemoLog.calc("회전 통과 ${turn.type}")
        }
        val cur = tr.current
        if (cur != null) {
            sink?.onSignal(Tbt(now, cur.type, tr.remainingM))
            DemoLog.calc("남은 거리 ${m(tr.remainingM)}m (${cur.type})")
            SignalThread.handler.postAtTime(::tick, token, SystemClock.uptimeMillis() + TICK_MS)
        } else {
            DemoLog.calc("회전 목록을 모두 지났다")
        }
    }

    private fun m(v: Double) = String.format(Locale.ROOT, "%.0f", v)

    private companion object {
        const val WAIT_MS = 150_000L
        const val POLL_MS = 1_000L
        const val TICK_MS = 100L
    }
}
