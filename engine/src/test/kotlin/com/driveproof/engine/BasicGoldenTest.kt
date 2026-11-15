package com.driveproof.engine

import com.driveproof.model.BasicKind
import com.driveproof.engine.Golden.col
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 신호 처리와 기본 항목을 demo-sim.js와 비교한다(E-2 완료 기준) */
class BasicGoldenTest {

    private fun runScenario(id: String): Pair<TripResult, List<Tick>> {
        val ticks = ArrayList<Tick>()
        val engine = TripEngine()
        engine.onTick = { ticks += it }
        return Golden.run(engine, Golden.signals(id)) to ticks
    }

    @Test
    fun smoothedSpeedAccelAndDistanceMatchReferenceTicks() {
        for (id in Golden.ids) {
            val g = Golden.ticks(id)
            val (_, ticks) = runScenario(id)
            val ts = g.col("t")
            assertEquals("$id 칸 수", ts.size, ticks.size)
            // 기어 P 뒤의 거리는 주행 밖이다. 거리는 주행 끝 칸까지만 본다
            val endI = g.getValue("endI").jsonPrimitive.int
            for (i in ts.indices) {
                val k = ticks[i]
                val at = "$id t=${k.t}ms"
                assertEquals("$at 시각", ts[i].jsonPrimitive.long, k.t)
                assertEquals("$at 속도", g.col("v")[i].jsonPrimitive.double, k.v, 0.0)
                assertEquals("$at 평활 속도", g.col("vs")[i].jsonPrimitive.double, k.vs, 1e-6)
                assertEquals("$at 가감속", g.col("a")[i].jsonPrimitive.double, k.a, 1e-6)
                if (i <= endI) assertEquals("$at 누적 거리", g.col("odo")[i].jsonPrimitive.double, k.odoM, 1e-5)
                assertEquals("$at 브레이크", g.col("brake")[i].jsonPrimitive.boolean, k.brake)
                assertEquals("$at 깜빡이", g.col("sig")[i].jsonPrimitive.content, k.sig.name)
                assertEquals("$at 기어", g.col("gear")[i].jsonPrimitive.content, k.gear.name)
                assertEquals("$at 조향", g.col("steer")[i].jsonPrimitive.double, k.steerDeg, 0.0)
            }
        }
    }

    @Test
    fun tripDistanceMatchesReference() {
        for (id in Golden.ids) {
            val (result, _) = runScenario(id)
            assertEquals("$id 주행거리(km)", Golden.result(id).getValue("km").jsonPrimitive.double, result.km, 1e-4)
            assertFalse("$id 끊긴 구간 없음", result.hadGap)
        }
    }

    /** 시각, 최대 가감속, 시작 속도, 면제(경고등 앞뒤 5초, 장면 구간)를 본다 */
    @Test
    fun basicEventsMatchReference() {
        for (id in Golden.ids) {
            val (result, _) = runScenario(id)
            val g = Golden.result(id).getValue("basic").jsonObject
            for ((key, kind) in listOf("accel" to BasicKind.ACCEL, "decel" to BasicKind.DECEL, "over" to BasicKind.OVER)) {
                val want = g.getValue(key).jsonArray.map { it.jsonObject }
                val got = result.basic.filter { it.kind == kind }
                assertEquals("$id $key 횟수", want.size, got.size)
                for ((w, r) in want.zip(got)) {
                    assertEquals("$id $key 시각", (w.getValue("t").jsonPrimitive.double * 1000).toLong(), r.at)
                    assertEquals("$id $key 최댓값", w.getValue("a").jsonPrimitive.double, r.value, 1e-6)
                    assertEquals("$id $key 시작 속도", w.getValue("v").jsonPrimitive.double, r.kmh, 1e-6)
                    val exempt = w.getValue("exempt")
                    assertEquals("$id $key 면제", if (exempt is JsonNull) "NONE" else exempt.jsonPrimitive.content.uppercase(), r.exempt.name)
                }
            }
        }
    }

    /** 이슈 완료 기준: 시나리오 5에서 급감속 1번(약 17km/h/s), 시나리오 1~4는 0건 */
    @Test
    fun scenario5HasOneHardBrakeAndTheOthersHaveNone() {
        for (id in listOf("s1", "s2", "s3", "s4")) assertEquals("$id 기본 항목", emptyList<BasicRec>(), runScenario(id).first.basic)
        val basic = runScenario("s5").first.basic
        assertEquals(1, basic.size)
        assertEquals(BasicKind.DECEL, basic[0].kind)
        assertEquals(17.3, basic[0].value, 0.05)
    }
}
