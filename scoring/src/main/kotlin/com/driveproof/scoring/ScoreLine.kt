package com.driveproof.scoring

/**
 * 한 장의 점수 줄. [key]는 `strings.xml`의 `card_score_*` 키이고 [args]는 그 틀에 넣을 값이다.
 * [total]은 이 주행을 반영한 뒤의 총점이고 [delta]는 반올림한 점수의 변화다. 첫 주행이면 [delta]가 null이다.
 */
data class ScoreLine(val key: String, val args: Map<String, Any>, val total: Double, val delta: Int?)

/**
 * 이 주행을 반영하기 전([before])과 뒤([after])의 점수로 점수 줄을 만든다(scoring.md "화면에 보여 주는 값").
 * 기록이 없던 첫 주행은 "기록 1회째", 그 뒤는 "이번 주행 +2점", "이번 주행 −1점", "점수 변화 없음"이다.
 */
fun scoreLine(before: Score?, after: Score): ScoreLine {
    if (before == null) return ScoreLine("card_score_first", mapOf("n" to after.trips), after.total, null)
    val d = (Math.round(after.total) - Math.round(before.total)).toInt()
    return when {
        d > 0 -> ScoreLine("card_score_up", mapOf("d" to d), after.total, d)
        d < 0 -> ScoreLine("card_score_down", mapOf("d" to -d), after.total, d)
        else -> ScoreLine("card_score_same", emptyMap(), after.total, 0)
    }
}
