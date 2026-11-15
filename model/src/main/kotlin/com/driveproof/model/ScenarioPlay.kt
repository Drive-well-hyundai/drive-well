package com.driveproof.model

/** 재생 격자. 10Hz다 */
const val REPLAY_TICK_MS = 100L

/** 마지막 줄 뒤에 더 내보내는 틱 수. demo-sim.js `simulate`와 같다 */
private const val TAIL_TICKS = 10

/**
 * 시나리오 JSON을 0.1초 격자의 시각순 신호 목록으로 바꾼다. `:source`의 `ScenarioPlayer`에 끼운다.
 * 신호의 `t`는 시나리오 시작부터의 ms다.
 */
fun playScenario(json: String): List<Signal> = play(parseScenario(json))

/**
 * 10Hz 재생기. 기준 구현은 demo-sim.js `compile`·`simulate`다(implementation.md "시나리오 파일 규격").
 *
 * - `speed_kmh`는 시점 사이를 직선으로 이은 뒤 1km/h로 반올림한다. 틱마다 내보낸다.
 * - `tbt.distance`와 `caution.remain`은 같은 종류가 이어지는 동안만 직선으로 잇는다.
 *   종류가 바뀌는 줄은 그 시점에 바로 바뀐다. 값이 바뀐 틱에만 내보낸다.
 * - 나머지 값은 다음 줄까지 그대로다. 줄에 적힌 틱에만 내보낸다.
 * - 격자에 맞지 않는 줄은 다음 틱에 내보낸다.
 * - 같은 틱 안에서는 내비 신호(경로 안내 상태, tbt, caution, 제한속도)를 먼저, 차량 신호를 나중에 내보낸다.
 *   그래서 같은 줄에서 켠 깜빡이는 그 줄의 `distance`를 켠 거리로 쓴다.
 * - `route`가 `vehicle`이면 `tbt`를 내보내지 않는다.
 */
fun play(scenario: Scenario): List<Signal> {
    val ev = scenario.events
    val speedKeys = ArrayList<SpeedPoint>()
    val tbtKeys = ArrayList<DistKey>()
    val cauKeys = ArrayList<DistKey>()
    var curTbtType: String? = null
    var curCau: DistKey? = null
    for (e in ev) {
        val t = e.t / 1000.0
        e.speedKmh?.let { speedKeys += SpeedPoint(t, it) }
        e.tbt?.let { b ->
            val type = b.type ?: curTbtType ?: throw IllegalArgumentException("첫 tbt에 type이 없다 (t=${e.t})")
            tbtKeys += DistKey(t, type, b.distance, null, clear = false, jump = b.type != null && b.type != curTbtType)
            curTbtType = type
        }
        if (e.hasCaution) {
            val c = e.caution
            if (c == null) {
                cauKeys += DistKey(t, null, 0.0, null, clear = true, jump = false)
                curCau = null
            } else {
                val prev = curCau
                val type = c.type ?: prev?.type ?: throw IllegalArgumentException("첫 caution에 type이 없다 (t=${e.t})")
                val key = DistKey(t, type, c.remain, c.limit ?: prev?.limit, clear = false, jump = prev == null || (c.type != null && c.type != prev.type))
                cauKeys += key
                curCau = key
            }
        }
    }

    val out = ArrayList<Signal>()
    val end = ev.last().t / 1000.0
    val n = Math.round(end / 0.1).toInt() + TAIL_TICKS
    var hi = 0
    var lastTbt: DistNow? = null
    var lastCau: DistNow? = null
    for (i in 0..n) {
        val t = Math.round(i * 0.1 * 1000) / 1000.0
        val ms = i * REPLAY_TICK_MS
        val due = ArrayList<ScenarioEvent>()
        while (hi < ev.size && ev[hi].t / 1000.0 <= t + 1e-9) due += ev[hi++]

        // 내비 신호 먼저
        if (i == 0) out += NaviState(ms, scenario.naviRoute)
        if (scenario.naviRoute) {
            val now = distAt(tbtKeys, t)
            if (now != null && now != lastTbt) out += Tbt(ms, TurnType.fromName(now.type), now.d)
            lastTbt = now
        }
        val cau = distAt(cauKeys, t)
        if (cau != lastCau) {
            out += if (cau != null) Caution(ms, CautionType.fromName(cau.type), cau.d, cau.limit) else Caution(ms, null, null)
        }
        lastCau = cau
        for (e in due) e.speedLimitKmh?.let { out += RoadLimit(ms, it) }

        // 차량 신호 나중
        for (e in due) {
            e.gear?.let { out += Gear(ms, it) }
            e.turnSignal?.let { out += TurnSignal(ms, it) }
            e.brake?.let { out += Brake(ms, it) }
            e.steeringDeg?.let { out += Steering(ms, it) }
            for ((name, lamp) in e.telltale) out += Telltale(ms, name, lamp)
        }
        if (speedKeys.isNotEmpty()) out += Speed(ms, maxOf(0L, Math.round(lerp(speedKeys, t))).toDouble())
    }
    return out
}

private data class SpeedPoint(val t: Double, val v: Double)

private data class DistKey(val t: Double, val type: String?, val d: Double, val limit: Int?, val clear: Boolean, val jump: Boolean)

private data class DistNow(val type: String, val d: Double, val limit: Int?)

private fun lerp(keys: List<SpeedPoint>, t: Double): Double {
    if (t <= keys[0].t) return keys[0].v
    for (i in 0 until keys.size - 1) {
        if (t < keys[i + 1].t) {
            val a = keys[i]
            val b = keys[i + 1]
            return a.v + (b.v - a.v) * (t - a.t) / (b.t - a.t)
        }
    }
    return keys.last().v
}

/** [t]의 종류와 거리. 아직 없거나 끝났으면 null이다 */
private fun distAt(keys: List<DistKey>, t: Double): DistNow? {
    var i = -1
    for (k in keys.indices) {
        if (keys[k].t <= t + 1e-9) i = k else break
    }
    if (i < 0 || keys[i].clear) return null
    val a = keys[i]
    val b = keys.getOrNull(i + 1)
    var d = a.d
    if (b != null && !b.clear && !b.jump && b.type == a.type) d = a.d + (b.d - a.d) * (t - a.t) / (b.t - a.t)
    return DistNow(a.type!!, d, a.limit)
}
