package com.driveproof.engine

import com.driveproof.model.Principle
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 회전 찾기와 미리 켜기 장면을 demo-sim.js와 비교한다(E-3 완료 기준) */
class SignalSceneGoldenTest {

    private val kinds = mapOf("좌회전" to SceneKind.LEFT_TURN, "우회전" to SceneKind.RIGHT_TURN, "유턴" to SceneKind.U_TURN)

    private fun signalScenes(id: String) =
        Golden.run(TripEngine(), Golden.signals(id)).scenes.filter { it.principle == Principle.SIGNAL }

    @Test
    fun signalScenesMatchReference() {
        for (id in Golden.ids) {
            val want = Golden.result(id).getValue("scenes").jsonArray.map { it.jsonObject }
                .filter { it.getValue("principle").jsonPrimitive.content == "signal" }
            val got = signalScenes(id)
            assertEquals("$id 장면 수", want.size, got.size)
            for ((w, s) in want.zip(got)) {
                val at = "$id t=${s.at}ms"
                assertEquals("$at 종류", kinds.getValue(w.getValue("kind").jsonPrimitive.content), s.kind)
                assertEquals("$at 시각", (w.getValue("t").jsonPrimitive.double * 1000).toLong(), s.at)
                assertEquals("$at 추정", w.getValue("est").jsonPrimitive.boolean, s.estimated)
                assertEquals("$at 부분 점수", w.getValue("s").jsonPrimitive.double, s.s, 0.01)
                val value = w.getValue("value")
                if (value is JsonNull) assertNull("$at 깜빡이 없음", s.onDistanceM)
                else assertEquals("$at 켠 거리", value.jsonPrimitive.double, s.onDistanceM!!, 1e-4)
            }
        }
    }

    /** 이슈 완료 기준: 시나리오 1은 좌회전 15m, 0.50 */
    @Test
    fun scenario1IsALeftTurnSignalledFifteenMetresBefore() {
        val s = signalScenes("s1").single()
        assertEquals(SceneKind.LEFT_TURN, s.kind)
        assertEquals(ScenePath.NAVI, s.path)
        assertEquals(15.0, s.onDistanceM!!, 1e-9)
        assertEquals(0.50, s.s, 1e-9)
    }

    /** 이슈 완료 기준: 시나리오 2는 우회전 약 20m(추정), 깜빡이 없는 좌회전 1번 */
    @Test
    fun scenario2FindsTwoTurnsWithoutNavigation() {
        val (right, left) = signalScenes("s2")
        assertEquals(SceneKind.RIGHT_TURN, right.kind)
        assertEquals(ScenePath.VEHICLE, right.path)
        assertEquals(20.0, right.onDistanceM!!, 0.5)
        assertEquals(SceneKind.LEFT_TURN, left.kind)
        assertEquals(ScenePath.VEHICLE, left.path)
        assertNull(left.onDistanceM)
        assertEquals(0.0, left.s, 0.0)
    }
}
