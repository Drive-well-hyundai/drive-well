package com.driveproof.engine

import com.driveproof.model.BasicKind
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import com.driveproof.model.Blink
import com.driveproof.model.Brake
import com.driveproof.model.Caution
import com.driveproof.model.CautionType
import com.driveproof.model.Consts
import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.Heading
import com.driveproof.model.Lamp
import com.driveproof.model.NaviState
import com.driveproof.model.Odometer
import com.driveproof.model.RoadLimit
import com.driveproof.model.Signal
import com.driveproof.model.Speed
import com.driveproof.model.Steering
import com.driveproof.model.Tbt
import com.driveproof.model.Telltale
import com.driveproof.model.TurnSignal
import com.driveproof.model.TurnType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** 격자 한 칸에서 본 값. 판정은 이 값으로만 한다 */
internal data class Tick(
    val i: Long,
    val t: Long,
    /** 마지막으로 받은 속도(km/h) */
    val v: Double,
    /** 1초 이동 평균 속도 */
    val vs: Double,
    /** 평활 속도의 1초 차분(km/h/s). 가속이 양수다 */
    val a: Double,
    /** 주행 시작부터 속도를 적분한 거리(m) */
    val odoM: Double,
    val gear: GearPos,
    val sig: Blink,
    val brake: Boolean,
    val steerDeg: Double,
    /** 요각 속도(도/초). 왼쪽이 음수다 */
    val omega: Double,
    val headingTurn: Pair<Int, Double>?,
    val hasSteering: Boolean,
)

/**
 * 신호를 받아 0.1초 격자로 판정한다. 기준 구현은 demo-sim.js `simulate`·`detect`다.
 *
 * - 신호가 실차에서 왔는지 재생에서 왔는지 모른다. 시각은 신호의 `t`에서만 읽는다.
 * - 격자는 처음 받은 신호의 시각에서 시작한다. 칸마다 그때까지 받은 마지막 값으로 계산한다.
 *   그래서 같은 값이 두 번 와도 결과가 같다.
 * - 신호 처리(평활, 가감속)는 주행 사이에도 끊지 않는다. 거리, 이벤트, 장면만 주행마다 새로 센다.
 * - 주행은 [end] 뒤 다시 움직일 때(기어가 P가 아니고 속도 > 0) 갈린다.
 *   [end]의 시각에서 30초 안이면 같은 주행을 잇고, 아니면 새 주행이다(driving-record.md "주행의 시작과 끝").
 * - dp-signal 스레드 하나에서만 부른다. 잠금이 없다.
 */
class TripEngine(private val c: Consts = Consts(), private val controlsEnabled: Boolean = true) {

    // 마지막으로 받은 값. 처음 상태는 demo-sim.js와 같다
    private var gear = GearPos.P
    private var sig = Blink.OFF
    private var brake = false
    private var steerDeg = 0.0
    private var hasSteering = false
    private val headingTurn = HeadingTurn(c)
    private var speedKmh = 0.0
    private var lastSpeedAt: Long? = null
    private var limitKmh = 0
    private var tbt: Tbt? = null
    private var tbtFresh = false
    private var tbtOdoM = 0.0
    private var caution: Caution? = null
    private val telltales = HashMap<String, Lamp>()
    private var routeDrive = false

    // 격자
    private var nextTick: Long? = null
    private var tickNo = 0L
    private val raw = ArrayDeque<Double>()      // 최근 1초의 속도
    private val smooth = ArrayDeque<Double>()   // 최근 1초의 평활 속도 (1초 전 값과의 차분용)
    private var prevSig: Blink? = null
    private val history = ArrayDeque<Tick>()    // 최근 칸. 50m 구간을 거슬러 볼 때 쓴다
    private val lampOn = HashMap<String, Boolean>() // 직전 칸의 경고등 상태
    private var streamTicks = 0L                // 신호를 받기 시작한 뒤의 칸 수

    // 주행
    private var odoM = 0.0
    private var firstTickOfTrip = true
    private var hadGap = false
    private var guided = false                  // 이 주행에서 경로 안내를 받은 적이 있다 (안내 상태 또는 회전 안내)
    private var hadCaution = false              // 이 주행에서 주의 지점 이벤트가 온 적이 있다
    private var endedAt: Long? = null
    private val basic = ArrayList<Counted>()
    private val runs = HashMap<BasicKind, Run>()
    private val lastCounted = HashMap<BasicKind, Long>()
    private var basicConfirmed = 0

    // 회전
    private var sigRunOdoM = 0.0                // 지금 깜빡이 상태가 시작된 곳
    private var nav: Nav? = null
    private var episode: Episode? = null
    private val naviScenes = ArrayList<SceneRec>()
    private val vehicleTurns = ArrayList<VehicleTurn>()
    private var scenesConfirmed = 0

    // 주의 지점
    private var bumpPassed: Boolean? = null     // 안내 중인 과속방지턱을 넘었나. 안내가 없으면 null
    private var zone: Zone? = null              // 지나고 있는 스쿨존
    private var zoneDone = false                // 끝난 스쿨존의 안내가 아직 이어지고 있다

    // 위험 장면
    private val risks = ArrayList<Risk>()
    private var risksConfirmed = 0

    /** 칸마다 불린다. 테스트와 판정 기록용이다 */
    internal var onTick: ((Tick) -> Unit)? = null

    /** 기본 항목 이벤트를 세는 순간 불린다. 시연 판정 기록(`DP_DEMO`)에 쓴다 */
    var onBasic: ((BasicRec) -> Unit)? = null

    /** 경고등이 꺼짐에서 켜짐·깜빡임으로 바뀐 순간 불린다. 시연 판정 기록에 쓴다 */
    var onRisk: ((RiskRec) -> Unit)? = null

    /** 내비 경로의 회전을 통과해 장면(미리 켜기, 미리 줄이기)이 확정되는 순간 불린다. 시연 판정 기록에 쓴다 */
    var onScene: ((SceneRec) -> Unit)? = null

    fun onSignal(s: Signal) {
        advance(s.t)
        when (s) {
            is Speed -> { speedKmh = s.kmh; lastSpeedAt = s.t }
            is Gear -> gear = s.gear
            is TurnSignal -> sig = s.state
            is Brake -> brake = s.pressed
            is Steering -> {
                if (hasSteering != s.available) {
                    episode = null
                    headingTurn.clear()
                }
                hasSteering = s.available
                steerDeg = if (s.available) s.deg else 0.0
            }
            is Heading -> if (!hasSteering && controlsEnabled) headingTurn.observe(s)
            is Telltale -> telltales[s.name] = s.state
            is Tbt -> { tbt = s; tbtFresh = true }
            is Caution -> caution = if (s.type == null) null else s
            is RoadLimit -> limitKmh = s.kmh
            is NaviState -> routeDrive = s.routeDrive
            is Odometer -> Unit // 주행거리는 :service가 Odometer와 적분 중에 고른다
        }
    }

    /**
     * 확정되고 5초 지난 장면과 이벤트. 한 번 낸 것은 다시 내지 않는다.
     * 추정 회전(차량 신호 경로)은 주차 직전 200m 규칙 때문에 주행이 끝나야 정해진다. [end]의 결과에만 있다.
     * 그래서 추정 회전 구간의 급감속은 여기서는 면제 없이 나온다. 면제까지 맞는 값은 [end]의 결과다.
     */
    fun confirmed(): List<Confirmed> {
        val now = nextTick ?: return emptyList()
        val out = ArrayList<Confirmed>()
        while (basicConfirmed < basic.size && basic[basicConfirmed].startT + CONFIRM_MS <= now) {
            out += exempted(basic[basicConfirmed++].toRec(), naviScenes)
        }
        while (scenesConfirmed < naviScenes.size && naviScenes[scenesConfirmed].at + CONFIRM_MS <= now) {
            val scene = naviScenes[scenesConfirmed++]
            if (!inLampWindow(scene)) out += scene
        }
        while (risksConfirmed < risks.size && risks[risksConfirmed].t + c.exemptMs <= now) {
            out += risks[risksConfirmed++].toRec()
        }
        return out
    }

    /**
     * 기어 P 때 부른다. [t] 앞까지의 칸을 계산하고 지금 주행의 결과를 낸다.
     * 30초 안에 다시 출발하면 같은 주행을 이어서 세고, 다음 [end]가 주행 전체의 결과를 다시 낸다.
     */
    fun end(t: Long): TripResult {
        advance(t)
        endedAt = t
        val km = odoM / 1000.0
        val all = scenesAtEnd()
        val events = basic.map { exempted(it.toRec(), all) }
        // 경고등 구간과 겹치는 미리 줄이기 장면은 판정하지 않는다
        val scenes = if (km < c.minKm) emptyList() else all.filterNot(::inLampWindow)
        return TripResult(
            km = km,
            scenes = scenes,
            basic = events,
            risks = risks.map { it.toRec() },
            hadGap = hadGap,
            card = buildCard(scenes, events, guided, hadCaution, c),
        )
    }

    /**
     * 회전 50m 구간 안(시작 0.5초 전부터)의 급감속은 그 장면에만 반영한다.
     * 같은 행동을 두 번 깎지 않는다(driving-record.md "미리 줄이기").
     */
    private fun exempted(r: BasicRec, scenes: List<SceneRec>): BasicRec {
        if (r.kind != BasicKind.DECEL) return r
        // 경고등 앞뒤 5초가 먼저다 (driving-record.md "위험 장면")
        if (risks.any { it.exempts && r.at >= it.t - c.exemptMs && r.at <= it.t + c.exemptMs }) return r.copy(exempt = Exempt.TELLTALE)
        val inScene = scenes.any { s ->
            val from = s.windowFrom
            val to = s.windowTo
            from != null && to != null && r.at >= from - c.sceneLeadMs && r.at <= to
        }
        return if (inScene) r.copy(exempt = Exempt.SCENE) else r
    }

    private fun inLampWindow(s: SceneRec): Boolean {
        if (s.principle != Principle.DECEL) return false
        val from = s.windowFrom ?: return false
        val to = s.windowTo ?: return false
        return risks.any { it.exempts && from <= it.t + c.exemptMs && to >= it.t - c.exemptMs }
    }

    /** 주행 끝에서 본 장면. 안내 중이던 회전과 추정 회전을 더한다. 상태는 바꾸지 않는다(주행이 다시 열릴 수 있다) */
    private fun scenesAtEnd(): List<SceneRec> {
        val scenes = ArrayList(naviScenes)
        nav?.let { scenes += scenesOf(it) }
        zone?.let { scenes += zoneScene(it, nextTick ?: it.t0) } // 구역을 지나다 주차했다
        val naviTimes = scenes.map { it.at }
        var keptOdoM: Double? = null
        for (v in if (controlsEnabled) vehicleTurns else emptyList()) {
            val kept = keptOdoM
            val drop = v.odoM < c.edgeM ||                                   // 주행 시작 200m 안
                odoM - v.odoM < c.edgeM ||                                   // 주차 직전 200m 안
                naviTimes.any { abs(it - v.t) <= c.dedupeMs } ||             // 내비 경로가 같은 회전을 잡음
                (kept != null && v.odoM - kept < c.turnGapM)                 // 앞 회전과 50m 미만
            if (drop) continue
            keptOdoM = v.odoM
            scenes += signalScene(v.kind, v.t, v.onDistanceM, ScenePath.VEHICLE)
            scenes += decelScene(v.kind, v.t, v.window, v.passKmh, ScenePath.VEHICLE)
        }
        scenes.sortBy { it.at } // 같은 시각은 넣은 순서를 지킨다
        return scenes
    }

    /** [until]보다 앞선 칸을 모두 계산한다. 같은 시각의 신호를 다 받은 뒤에 그 칸을 계산하려는 것이다 */
    private fun advance(until: Long) {
        var t = nextTick ?: run { nextTick = until; return }
        if (until < t - c.tickMs) {
            // 시각이 거꾸로 갔다(재생을 새로 시작했다). 격자를 새로 잡고 새 주행으로 센다
            resetSignalWindow()
            resetTrip()
            lastSpeedAt = null
            endedAt = null
            nextTick = until
            return
        }
        while (t < until) {
            val last = lastSpeedAt
            if (last == null || t - last > c.speedGapMs) {
                // 속도가 3초 넘게 비었다. 이 구간은 판정하지 않고, 남은 빈 칸은 건너뛴다
                // 서 있는 동안 빈 것은 판정할 것이 없으니 끊긴 구간으로 적지 않는다
                if (last != null && !firstTickOfTrip && speedKmh > 0.0) hadGap = true
                resetSignalWindow()
                val skip = (until - t - 1) / c.tickMs
                t += (skip + 1) * c.tickMs
                tickNo += skip + 1
                continue
            }
            tick(t)
            t += c.tickMs
        }
        nextTick = t
    }

    /** 신호가 끊겼다. 이어지던 판정을 버린다 */
    private fun resetSignalWindow() {
        raw.clear()
        smooth.clear()
        runs.clear()
        history.clear()
        lampOn.clear()
        streamTicks = 0
        nav = null
        episode = null
        headingTurn.clear()
        prevSig = null
        bumpPassed = null
        zone = null
        zoneDone = false
    }

    private fun tick(t: Long) {
        val v = speedKmh
        startNewTripIfMovedAgain(t, v)

        raw.addLast(v)
        if (raw.size > TICKS_PER_SEC) raw.removeFirst()
        val vs = raw.sum() / raw.size
        // 1초 전 평활 속도가 있어야 차분을 낸다
        val a = if (smooth.size == TICKS_PER_SEC) vs - smooth.first() else 0.0
        smooth.addLast(vs)
        if (smooth.size > TICKS_PER_SEC) smooth.removeFirst()

        if (!firstTickOfTrip) odoM += (v / 3.6) * (c.tickMs / 1000.0)
        firstTickOfTrip = false
        if (routeDrive) guided = true
        if (caution != null) hadCaution = true

        // 요각 속도 (driving-record.md "요각 추정")
        val omega = if (gear != GearPos.R && v >= c.turnMinKmh && abs(steerDeg) >= c.steerDeadDeg) {
            (v / 3.6) * tan((steerDeg * Math.PI / 180) / c.steerRatio) / c.wheelbaseM * 180 / Math.PI
        } else {
            0.0
        }

        if (gear == GearPos.R || v < c.turnMinKmh) headingTurn.clear()
        val heading = if (hasSteering) null else headingTurn.consume(t)
        val k = Tick(tickNo, t, v, vs, a, odoM, gear, sig, brake, steerDeg, omega, heading, hasSteering)
        history.addLast(k)
        if (history.size > HISTORY_TICKS) history.removeFirst()
        detectBasic(k)
        val sigChanged = prevSig != null && prevSig != k.sig
        if (prevSig != k.sig) sigRunOdoM = k.odoM
        trackNaviTurn(k, sigChanged)
        trackVehicleTurn(k)
        trackCaution(k)
        trackLamps(k)
        prevSig = k.sig
        onTick?.invoke(k)
        tickNo++
    }

    private fun startNewTripIfMovedAgain(t: Long, v: Double) {
        val ended = endedAt ?: return
        if (gear == GearPos.P || v <= 0.0) return
        endedAt = null
        if (t >= ended && t - ended < c.resumeWindowMs) return // 같은 주행을 잇는다
        resetTrip()
        // 이 칸에서 이미 움직이고 있다. 이 칸의 거리부터 센다
        firstTickOfTrip = false
    }

    private fun resetTrip() {
        odoM = 0.0
        firstTickOfTrip = true
        hadGap = false
        guided = false
        hadCaution = false
        basic.clear()
        runs.clear()
        lastCounted.clear()
        basicConfirmed = 0
        sigRunOdoM = 0.0
        tbtOdoM = 0.0
        history.clear()
        nav = null
        episode = null
        headingTurn.clear()
        naviScenes.clear()
        vehicleTurns.clear()
        scenesConfirmed = 0
        bumpPassed = null
        zone = null
        zoneDone = false
        risks.clear()
        risksConfirmed = 0
    }

    /** 급가속, 급감속, 과속 (driving-record.md "신호 처리", "기본 항목") */
    private fun detectBasic(k: Tick) {
        if (abs(k.a) <= c.noiseKmhPerS) {
            track(BasicKind.ACCEL, k, k.vs >= c.basicMinKmh && k.a >= c.accelThreshold(k.vs), abs(k.a))
            track(BasicKind.DECEL, k, k.vs >= c.basicMinKmh && k.brake && -k.a >= c.decelThreshold(k.vs), abs(k.a))
        }
        // 제한속도가 0이면 모르는 것이다. 세지 않는다
        track(BasicKind.OVER, k, limitKmh > 0 && k.vs > limitKmh + c.overMarginKmh, k.vs)
    }

    /** 조건이 [Consts.sustainTicks]칸 이어지면 1회로 센다. 인정 뒤 [Consts.coolMs] 동안은 같은 종류를 세지 않는다 */
    private fun track(kind: BasicKind, k: Tick, over: Boolean, value: Double) {
        if (!over) {
            runs.remove(kind)
            return
        }
        val r = runs.getOrPut(kind) { Run(k.i, k.t, k.vs) }
        r.peak = max(r.peak, value)
        val counted = r.counted
        if (counted != null) {
            counted.peak = r.peak
            return
        }
        val last = lastCounted[kind]
        if (k.i - r.startI + 1 >= c.sustainTicks && (last == null || r.startT - last >= c.coolMs)) {
            val ev = Counted(kind, r.startT, r.startVs, r.peak)
            r.counted = ev
            basic += ev
            lastCounted[kind] = r.startT
            onBasic?.invoke(ev.toRec())
        }
    }

    /**
     * 내비 경로 (driving-record.md "회전 통과", "미리 켜기").
     * 남은 거리가 최솟값보다 5m 넘게 커지거나 안내가 다른 종류로 바뀌면 통과다.
     * 통과 시각은 최솟값을 받은 시각이고, 판정은 그때의 깜빡이 상태로 한다.
     */
    private fun trackNaviTurn(k: Tick, sigChanged: Boolean) {
        val now = tbt
        if (now != null && tbtFresh) {
            tbtOdoM = k.odoM
            tbtFresh = false
            // 회전 안내를 받았으면 경로 안내 중이다. 안내 상태(NavigationStatus)는 바뀔 때만 와서
            // 안내를 미리 걸어 둔 채 신호를 받기 시작하면 오지 않을 수 있다
            guided = true
        }
        var n = nav
        if (n != null && (now == null || now.type != n.type || now.distanceM > n.minM + c.passSlackM)) {
            for (scene in scenesOf(n)) {
                naviScenes += scene
                onScene?.invoke(scene)
            }
            n = null
            nav = null
        }
        if (now == null) return
        if (n == null) {
            val dir = directionOf(now.type) ?: return
            // 안내가 시작될 때 이미 같은 방향 깜빡이가 켜져 있으면 그때의 남은 거리(최대 200m)를 켠 거리로 본다
            val on = if (k.sig == dir) min(c.signalCapM, now.distanceM) else null
            nav = Nav(now.type, dir, now.distanceM, k, on)
            return
        }
        if (sigChanged) {
            // 갱신 사이에 켰으면 마지막 갱신값에서 그 뒤 달린 거리를 뺀다
            val remainM = max(0.0, now.distanceM - (k.odoM - tbtOdoM))
            n.onM = if (k.sig == n.dir) min(c.signalCapM, remainM) else null
        }
        if (now.distanceM < n.minM) {
            n.minM = now.distanceM
            n.minTick = k
            n.onAtPassM = n.onM
        }
    }

    /**
     * 통과한 회전의 장면 둘(미리 켜기, 미리 줄이기).
     * 통과 직전 값이 50m보다 멀면 통과 시점을 믿을 수 없다. 장면을 버린다.
     */
    private fun scenesOf(n: Nav): List<SceneRec> {
        if (n.minM > c.naviMinM) return emptyList()
        val kind = when {
            n.type == TurnType.TURN_UTURN -> SceneKind.U_TURN
            n.dir == Blink.LEFT -> SceneKind.LEFT_TURN
            else -> SceneKind.RIGHT_TURN
        }
        val pass = n.minTick
        // 회전 지점은 최솟값을 받은 곳에서 그 값만큼 더 간 곳이다
        val window = decelWindow(pass, pass.odoM + n.minM)
        return buildList {
            if (controlsEnabled) add(signalScene(kind, pass.t, n.onAtPassM, ScenePath.NAVI))
            add(decelScene(kind, pass.t, window, pass.v, ScenePath.NAVI))
        }
    }

    /**
     * [end] 칸에서 거슬러 올라가 [targetOdoM] 50m 전부터의 최대 감속과 부분 점수를 구한다(scoring.md "1").
     * 5km/h/s 이하면 1, 구간 시작 속도의 급감속 기준 이상이면 0, 사이는 직선이다.
     */
    private fun decelWindow(end: Tick, targetOdoM: Double): DecelWindow {
        var i = history.size - 1 - (tickNo - end.i).toInt().coerceAtLeast(0)
        if (i < 0 || history[i].i != end.i) i = history.indexOfLast { it.i == end.i }
        if (i < 0) return DecelWindow(end.t, end.t, max(0.0, -end.a), c.decelThreshold(end.vs)).also { it.s = scoreDecel(it) }
        var first = i
        while (first > 0 && history[first - 1].odoM >= targetOdoM - c.decelWindowM) first--
        var worst = 0.0
        for (j in first..i) worst = max(worst, -history[j].a)
        val w = DecelWindow(history[first].t, end.t, worst, c.decelThreshold(history[first].vs))
        w.s = scoreDecel(w)
        return w
    }

    private fun scoreDecel(w: DecelWindow): Double = when {
        w.maxDecel <= c.gentleDecel + 1e-6 -> 1.0
        w.maxDecel >= w.hardDecel -> 0.0
        else -> 1 - (w.maxDecel - c.gentleDecel) / (w.hardDecel - c.gentleDecel)
    }

    private fun decelScene(kind: SceneKind, at: Long, w: DecelWindow, passKmh: Double, path: ScenePath) = SceneRec(
        principle = Principle.DECEL,
        kind = kind,
        at = at,
        s = w.s,
        path = path,
        maxDecel = w.maxDecel,
        hardDecel = w.hardDecel,
        passKmh = passKmh,
        windowFrom = w.from,
        windowTo = w.to,
    )

    /** 부분 점수 = min(1, 켠 거리 ÷ 30m). 안 켰으면 0 */
    private fun signalScene(kind: SceneKind, at: Long, onM: Double?, path: ScenePath) = SceneRec(
        principle = Principle.SIGNAL,
        kind = kind,
        at = at,
        s = if (onM == null) 0.0 else min(1.0, onM / c.signalRefM),
        path = path,
        onDistanceM = onM,
        criterionM = c.signalRefM,
    )

    private fun directionOf(type: TurnType): Blink? = when (type) {
        TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_UTURN -> Blink.LEFT
        TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> Blink.RIGHT
        else -> null
    }

    /**
     * 차량 신호 경로 (driving-record.md "요각 추정").
     * 핸들이 한쪽으로 꺾여 있는 동안을 한 구간으로 보고 요각을 적분한다. 구간이 끝날 때 회전인지 가린다.
     */
    private fun trackVehicleTurn(k: Tick) {
        if (!k.hasSteering && k.headingTurn == null) {
            episode = null
            return
        }
        val sign = k.headingTurn?.first
            ?: if (abs(k.steerDeg) >= c.steerDeadDeg) (if (k.steerDeg < 0) -1 else 1) else 0
        var e = episode
        if (e != null && (sign == 0 || sign != e.sign)) {
            finishEpisode(e, k.t, flipped = sign != 0)
            e = null
            episode = null
        }
        if (e == null) {
            if (sign == 0) return
            e = Episode(sign, k.t)
            episode = e
        }
        e.psiDeg += k.headingTurn?.second ?: (k.omega * (c.tickMs / 1000.0))
        if (k.v > c.turnMaxKmh) e.tooFast = true
        if (e.start == null && abs(e.psiDeg) > c.turnStartDeg) {
            // 회전 시작. 켠 거리는 켠 곳부터 여기까지 달린 거리다
            val dir = if (sign < 0) Blink.LEFT else Blink.RIGHT
            val on = if (k.sig == dir) min(c.signalCapM, k.odoM - sigRunOdoM) else null
            e.start = TurnStart(k.t, k.odoM, on, decelWindow(k, k.odoM), k.v)
        }
        if (e.reachedTurnAt == null && abs(e.psiDeg) >= c.turnDeg) e.reachedTurnAt = k.t
    }

    private fun finishEpisode(e: Episode, endT: Long, flipped: Boolean) {
        val deg = abs(e.psiDeg)
        if (deg < c.turnDeg) return
        val start = e.start ?: return
        val reached = e.reachedTurnAt ?: return
        val seconds = max(0.1, (endT - e.t0) / 1000.0)
        val drop = flipped ||                                   // S자 길
            e.tooFast ||                                        // 40km/h 넘는 구간
            reached - start.t > c.turnWithinMs ||               // 10초 안에 60도를 못 돎
            deg / seconds < c.turnMinRateDegPerS                // 완만한 커브
        if (drop) return
        val kind = when {
            deg >= c.uturnDeg -> SceneKind.U_TURN
            e.sign < 0 -> SceneKind.LEFT_TURN
            else -> SceneKind.RIGHT_TURN
        }
        vehicleTurns += VehicleTurn(start.t, start.odoM, kind, start.onDistanceM, start.window, start.passKmh)
    }

    /**
     * 주의 지점 (driving-record.md "미리 줄이기", "구역 지키기").
     * 과속방지턱은 남은 거리가 0.5m 이하가 되는 순간 통과다. 감속 점수와 통과 속도 점수 중 낮은 값을 쓴다.
     * 스쿨존은 남은 거리가 0.5m 이하가 되면 진입이고 진입 뒤 300m까지다. 구역 안 최고 속도로 점수를 낸다.
     * 주의 지점 이벤트가 온 주행에서만 판정한다.
     */
    private fun trackCaution(k: Tick) {
        val now = caution
        val remainM = now?.remainM
        val arrived = remainM != null && remainM <= c.cautionPassM

        if (now?.type == CautionType.SPEED_BUMP) {
            if (bumpPassed == null) bumpPassed = false
            if (bumpPassed == false && arrived) {
                bumpPassed = true
                val w = decelWindow(k, k.odoM)
                val passScore = when {
                    k.v <= c.bumpOkKmh -> 1.0
                    k.v >= c.bumpBadKmh -> 0.0
                    else -> 1 - (k.v - c.bumpOkKmh) / (c.bumpBadKmh - c.bumpOkKmh)
                }
                val scene = SceneRec(
                    principle = Principle.DECEL,
                    kind = SceneKind.SPEED_BUMP,
                    at = k.t,
                    s = min(w.s, passScore),
                    path = ScenePath.NAVI,
                    maxDecel = w.maxDecel,
                    hardDecel = w.hardDecel,
                    passKmh = k.v,
                    windowFrom = w.from,
                    windowTo = w.to,
                    decelScore = w.s,
                    passScore = passScore,
                )
                naviScenes += scene
                onScene?.invoke(scene)
            }
        } else {
            bumpPassed = null
        }

        val atZone = now?.type == CautionType.SCHOOL_ZONE && arrived
        // 끝난 스쿨존은 같은 안내가 이어지는 동안 다시 판정하지 않는다. 다음 스쿨존은 새 기회다
        if (atZone && zone == null && !zoneDone) zone = Zone(k.odoM, k.v, now?.limitKmh ?: c.zoneDefaultLimitKmh, k.t)
        var finished = false
        zone?.let { z ->
            z.maxKmh = max(z.maxKmh, k.v)
            if (k.odoM - z.odo0 >= c.zoneLengthM) {
                val scene = zoneScene(z, k.t)
                naviScenes += scene
                onScene?.invoke(scene)
                zone = null
                finished = true
            }
        }
        zoneDone = finished || (zoneDone && atZone)
    }

    /** s = 1 − (구역 안 최고 속도 − 제한속도) ÷ 20, 0~1로 자른다 */
    private fun zoneScene(z: Zone, at: Long) = SceneRec(
        principle = Principle.ZONE,
        kind = SceneKind.SCHOOL_ZONE,
        at = at,
        s = (1 - (z.maxKmh - z.limitKmh) / c.zoneSpanKmh).coerceIn(0.0, 1.0),
        path = ScenePath.NAVI,
        maxKmh = z.maxKmh,
        limitKmh = z.limitKmh,
    )

    private class Zone(val odo0: Double, var maxKmh: Double, val limitKmh: Int, val t0: Long)

    /**
     * 위험 장면 (driving-record.md "위험 장면").
     * 경고등이 꺼짐에서 켜짐·깜빡임으로 바뀐 순간을 적는다. 앞뒤 5초의 최대 감속은 그 뒤 5초 동안 이어서 잰다.
     * 신호를 받기 시작한 직후에 본 상태는 원래 상태다. 주행 시작 때 이미 켜져 있던 경고등은 면제에 쓰지 않는다.
     */
    private fun trackLamps(k: Tick) {
        for (r in risks) if (k.i - r.i in 1..EXEMPT_TICKS) r.maxDecel = max(r.maxDecel, -k.a)
        val baseline = streamTicks * c.tickMs < max(c.lampBaselineMs, c.tickMs)
        streamTicks++
        for (name in RISK_LAMPS) {
            val on = telltales[name].let { it != null && it != Lamp.OFF }
            val was = if (baseline) on else lampOn[name] ?: false
            lampOn[name] = on
            if (!on || was) continue
            // 5초 전부터 지금까지
            val back = history.filter { k.i - it.i in 0..EXEMPT_TICKS }
            val risk = Risk(name, k.t, k.i, back.maxOf { max(0.0, -it.a) }, back.first().v, k.brake, exempts = name != "DRIVER_ATTENTION")
            risks += risk
            onRisk?.invoke(risk.toRec())
        }
    }

    private class Risk(val lamp: String, val t: Long, val i: Long, var maxDecel: Double, val kmhBefore: Double, val brake: Boolean, val exempts: Boolean) {
        fun toRec() = RiskRec(lamp, t, maxDecel, kmhBefore, brake, exempts)
    }

    private class Run(val startI: Long, val startT: Long, val startVs: Double) {
        var peak = 0.0
        var counted: Counted? = null
    }

    /** 센 이벤트. 조건이 이어지는 동안 최댓값이 커질 수 있다 */
    private class Counted(val kind: BasicKind, val startT: Long, val startVs: Double, var peak: Double) {
        fun toRec() = BasicRec(kind, startT, peak, startVs)
    }

    /** 안내 중인 회전. [onM]은 지금 켜져 있는 같은 방향 깜빡이를 켠 거리, [onAtPassM]은 최솟값을 받은 순간의 그 값이다 */
    private class Nav(val type: TurnType, val dir: Blink, var minM: Double, var minTick: Tick, var onM: Double?) {
        var onAtPassM: Double? = onM
    }

    /** 핸들이 한쪽으로 꺾여 있는 구간 */
    private class Episode(val sign: Int, val t0: Long) {
        var psiDeg = 0.0
        var tooFast = false
        var start: TurnStart? = null
        var reachedTurnAt: Long? = null
    }

    private class TurnStart(val t: Long, val odoM: Double, val onDistanceM: Double?, val window: DecelWindow, val passKmh: Double)

    private class VehicleTurn(
        val t: Long,
        val odoM: Double,
        val kind: SceneKind,
        val onDistanceM: Double?,
        val window: DecelWindow,
        val passKmh: Double,
    )

    /** 회전 지점 50m 전부터의 구간. [from]부터 [to]까지이고 [s]는 부분 점수다 */
    private class DecelWindow(val from: Long, val to: Long, val maxDecel: Double, val hardDecel: Double) {
        var s = 1.0
    }

    private companion object {
        const val TICKS_PER_SEC = 10
        /** 경고등 면제 창(5초)이 닫힌 뒤에 확정한다 (implementation.md "스레드와 통지") */
        const val CONFIRM_MS = 5_000L
        /** 거슬러 볼 수 있는 칸 수(10분). 회전 앞에서 오래 서 있어도 50m 구간이 들어온다 */
        const val HISTORY_TICKS = 6_000
        /** 경고등 앞뒤로 보는 칸 수(5초) */
        const val EXEMPT_TICKS = 50
        /** 위험 장면으로 적는 경고등. Pleos Vehicle Safety 텔테일 이름이다 */
        val RISK_LAMPS = listOf("FCA", "ABS", "ESC", "DRIVER_ATTENTION")
    }
}
