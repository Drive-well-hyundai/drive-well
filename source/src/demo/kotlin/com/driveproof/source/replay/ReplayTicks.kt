package com.driveproof.source.replay

import com.driveproof.model.Signal

/** 같은 시각에 내보낼 신호 묶음. [signals]는 받은 순서를 지킨다(tbt·caution 먼저, 차량 신호 나중). */
data class ReplayTick(val t: Long, val signals: List<Signal>)

/**
 * 재생기 출력(시각순 신호 목록)을 시각별 묶음으로 나눈다.
 * 같은 시각 안의 순서는 바꾸지 않는다. 시각이 거꾸로 가면 거부한다.
 */
fun ticksOf(signals: List<Signal>): List<ReplayTick> {
    val ticks = ArrayList<ReplayTick>()
    var cur = ArrayList<Signal>()
    var curT = Long.MIN_VALUE
    for (s in signals) {
        require(s.t >= curT) { "재생 신호의 시각이 거꾸로 간다: ${s.t} < $curT" }
        if (s.t != curT && cur.isNotEmpty()) {
            ticks += ReplayTick(curT, cur)
            cur = ArrayList()
        }
        curT = s.t
        cur += s
    }
    if (cur.isNotEmpty()) ticks += ReplayTick(curT, cur)
    return ticks
}
