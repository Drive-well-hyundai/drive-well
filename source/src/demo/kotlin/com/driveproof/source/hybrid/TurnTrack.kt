package com.driveproof.source.hybrid

import com.driveproof.model.TurnType

/** 고정한 회전 목록의 한 항목. [distanceM]은 첫 항목은 출발 지점, 나머지는 앞 회전 지점부터의 거리(m)다 */
data class Turn(val type: TurnType, val distanceM: Double)

/**
 * 하이브리드의 남은 거리 계산. 규칙 원본은 demo-replay.md "구조"다.
 * 남은 거리 = 지금 항목 `distance` − ∫(라이브 속도)dt.
 * 0 이하가 되면 그 회전을 지난 것으로 보고 다음 항목으로 넘어간다. 0 아래로 넘친 거리는 다음 항목에서 뺀다.
 * 순수 Kotlin이다. 시각은 부르는 쪽이 준다.
 */
class TurnTrack(private val turns: List<Turn>) {

    var index = 0
        private set

    /** 지금 항목까지 남은 거리(m). 목록을 다 지나면 0 */
    var remainingM: Double = turns.firstOrNull()?.distanceM ?: 0.0
        private set

    val current: Turn? get() = turns.getOrNull(index)

    val finished: Boolean get() = index >= turns.size

    /**
     * [dtMs] 동안 [speedKmh]로 달렸다고 보고 줄인다.
     * @return 이번에 지난 회전들. 보통 비어 있거나 하나다
     */
    fun advance(dtMs: Long, speedKmh: Double): List<Turn> {
        if (finished || dtMs <= 0 || speedKmh <= 0.0) return emptyList()
        remainingM -= speedKmh / 3.6 * dtMs / 1000.0
        val passed = ArrayList<Turn>(1)
        while (!finished && remainingM <= 0.0) {
            passed += turns[index]
            val overflow = -remainingM
            index++
            remainingM = (current?.distanceM ?: 0.0) - overflow
        }
        if (finished) remainingM = 0.0
        return passed
    }
}
