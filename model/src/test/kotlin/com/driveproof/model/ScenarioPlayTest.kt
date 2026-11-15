package com.driveproof.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 재생기 출력을 demo-sim.js `simulate`의 틱과 비교한다(E-1 완료 기준).
 * 골든은 `tools/golden/export.js`가 만든 `golden/sN.ticks.json`이다.
 * 시나리오는 demo 빌드 에셋(`app/src/demo/assets/sN.json`)을 그대로 읽는다.
 */
class ScenarioPlayTest {

    /** 엔진이 격자마다 보게 될 값. 마지막으로 받은 값을 유지한다 */
    private class Held {
        var v = 0
        var gear = GearPos.P
        var sig = Blink.OFF
        var brake = false
        var steer = 0.0
        var tbt: Tbt? = null
        var cau: Caution? = null
        val tell = sortedMapOf<String, Lamp>()

        fun take(s: Signal) {
            when (s) {
                is Speed -> v = s.kmh.toInt()
                is Gear -> gear = s.gear
                is TurnSignal -> sig = s.state
                is Brake -> brake = s.pressed
                is Steering -> steer = s.deg
                is Tbt -> tbt = s
                is Caution -> cau = if (s.type == null) null else s
                is Telltale -> tell[s.name] = s.state
                is RoadLimit, is NaviState, is Odometer, is Heading -> Unit
            }
        }
    }

    private fun asset(id: String): String {
        // 경계 시나리오(b1~b6)는 골든 폴더에 있다. PT 시나리오는 demo 빌드 에셋이다
        if (id.startsWith("b")) return javaClass.getResource("/scenarios/$id.json")?.readText() ?: error("경계 시나리오가 없다: $id")
        // Gradle은 모듈 디렉터리(app/model)에서 테스트를 돌린다
        val f = File("../app/src/demo/assets/$id.json")
        assertTrue("에셋이 없다: ${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun golden(id: String): JsonObject {
        val text = javaClass.getResource("/$id.ticks.json")?.readText() ?: error("골든이 없다: $id.ticks.json")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.col(name: String): JsonArray = getValue(name).jsonArray

    private fun checkAgainstGolden(id: String) {
        val g = golden(id)
        val signals = playScenario(asset(id))
        val byT = signals.groupBy { it.t }
        val ts = g.col("t")
        val held = Held()
        assertEquals("$id 마지막 신호 시각", ts.last().jsonPrimitive.long, signals.last().t)

        for (i in ts.indices) {
            val t = ts[i].jsonPrimitive.long
            byT[t]?.forEach(held::take)
            val at = "$id t=${t}ms"
            assertEquals("$at 속도", g.col("v")[i].jsonPrimitive.int, held.v)
            assertEquals("$at 기어", g.col("gear")[i].jsonPrimitive.content, held.gear.name)
            assertEquals("$at 깜빡이", g.col("sig")[i].jsonPrimitive.content, held.sig.name)
            assertEquals("$at 브레이크", g.col("brake")[i].jsonPrimitive.boolean, held.brake)
            assertEquals("$at 조향", g.col("steer")[i].jsonPrimitive.double, held.steer, 0.0)
            assertEquals(
                "$at 경고등",
                g.col("tell")[i].jsonPrimitive.content,
                held.tell.filterValues { it != Lamp.OFF }.map { "${it.key}:${it.value}" }.joinToString(","),
            )

            val tbtType = g.col("tbtType")[i]
            if (tbtType is JsonNull) {
                assertNull("$at 회전 안내 없음", held.tbt)
            } else {
                assertEquals("$at 회전 종류", tbtType.jsonPrimitive.content, held.tbt?.type?.name)
                assertEquals("$at 회전까지 남은 거리", g.col("tbtD")[i].jsonPrimitive.double, held.tbt!!.distanceM, 1e-6)
            }

            val cauType = g.col("cauType")[i]
            if (cauType is JsonNull) {
                assertNull("$at 주의 지점 없음", held.cau)
            } else {
                assertEquals("$at 주의 지점 종류", cauType.jsonPrimitive.content, held.cau?.type?.name)
                assertEquals("$at 주의 지점까지 남은 거리", g.col("cauD")[i].jsonPrimitive.double, held.cau!!.remainM!!, 1e-6)
                val limit = g.col("cauLimit")[i]
                assertEquals("$at 스쿨존 제한속도", if (limit is JsonNull) null else limit.jsonPrimitive.int, held.cau!!.limitKmh)
            }
        }
    }

    @Test fun s1MatchesReferenceTicks() = checkAgainstGolden("s1")

    @Test fun s2MatchesReferenceTicks() = checkAgainstGolden("s2")

    @Test fun s3MatchesReferenceTicks() = checkAgainstGolden("s3")

    @Test fun s4MatchesReferenceTicks() = checkAgainstGolden("s4")

    @Test fun s5MatchesReferenceTicks() = checkAgainstGolden("s5")

    @Test
    fun boundaryScenariosMatchReferenceTicks() {
        for (id in listOf("b1", "b2", "b3", "b4", "b5", "b6")) checkAgainstGolden(id)
    }

    @Test
    fun timesAreOnTheGridAndNeverGoBack() {
        for (id in listOf("s1", "s2", "s3", "s4", "s5")) {
            var last = 0L
            for (s in playScenario(asset(id))) {
                assertEquals("$id 0.1초 격자", 0L, s.t % REPLAY_TICK_MS)
                assertTrue("$id 시각순", s.t >= last)
                last = s.t
            }
        }
    }

    @Test
    fun speedComesEveryTick() {
        val speeds = playScenario(asset("s1")).filterIsInstance<Speed>()
        assertEquals((0..1510).map { it * REPLAY_TICK_MS }, speeds.map { it.t })
    }

    /** 같은 줄에서 켠 깜빡이는 그 줄의 distance를 켠 거리로 쓴다(demo-replay.md "시나리오 형식") */
    @Test
    fun naviSignalsComeBeforeVehicleSignalsInTheSameTick() {
        val tick = playScenario(asset("s1")).filter { it.t == 100_500L }
        val tbt = tick.indexOfFirst { it is Tbt }
        val blink = tick.indexOfFirst { it is TurnSignal }
        assertTrue(tbt in 0 until blink)
        assertEquals(15.0, (tick[tbt] as Tbt).distanceM, 1e-9)
        assertEquals(Blink.LEFT, (tick[blink] as TurnSignal).state)
        assertTrue(tick.last() is Speed)
    }

    @Test
    fun routeIsAnnouncedFirstAndVehicleRouteDropsTbt() {
        assertEquals(NaviState(0, true), playScenario(asset("s1")).first())
        val json = """{"route":"vehicle","events":[
            {"t":0,"gear":"D","speed_kmh":0,"tbt":{"type":"TURN_LEFT","distance":100}},
            {"t":1000,"speed_kmh":10,"tbt":{"distance":90}}]}"""
        val signals = playScenario(json)
        assertEquals(NaviState(0, false), signals.first())
        assertTrue(signals.none { it is Tbt })
    }

    @Test
    fun distanceJumpsWhenTheTurnTypeChanges() {
        val tbts = playScenario(asset("s1")).filterIsInstance<Tbt>()
        // 좌회전 2m(102.0초)가 다음 안내(105.0초)까지 그대로다가 직진 400m로 바로 바뀐다
        assertEquals(Tbt(102_000, TurnType.TURN_LEFT, 2.0), tbts.last { it.type == TurnType.TURN_LEFT })
        assertEquals(listOf(Tbt(105_000, TurnType.TURN_STRAIGHT, 400.0)), tbts.filter { it.type == TurnType.TURN_STRAIGHT })
    }

    @Test
    fun cautionEndsWithNullAndCarriesTheSchoolZoneLimit() {
        val cautions = playScenario(asset("s3")).filterIsInstance<Caution>()
        assertEquals(Caution(70_000, CautionType.SCHOOL_ZONE, 200.0, 30), cautions.first())
        assertEquals(Caution(120_000, null, null), cautions.last())
        assertTrue(cautions.dropLast(1).all { it.limitKmh == 30 })
    }

    @Test
    fun offGridLineGoesOutOnTheNextTick() {
        val json = """{"route":"vehicle","events":[
            {"t":0,"gear":"D","speed_kmh":0},{"t":250,"turn_signal":"LEFT"},{"t":1000,"speed_kmh":10}]}"""
        assertEquals(TurnSignal(300, Blink.LEFT), playScenario(json).first { it is TurnSignal })
    }

    @Test
    fun speedIsRoundedToWholeKmh() {
        val json = """{"route":"vehicle","events":[{"t":0,"speed_kmh":0},{"t":1000,"speed_kmh":3}]}"""
        val speeds = playScenario(json).filterIsInstance<Speed>().take(11).map { it.kmh }
        assertEquals(listOf(0.0, 0.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 2.0, 3.0, 3.0), speeds)
    }

    @Test
    fun malformedScenarioIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { playScenario("{") }
        assertThrows(IllegalArgumentException::class.java) { playScenario("""{"route":"navi"}""") }
        assertThrows(IllegalArgumentException::class.java) { playScenario("""{"route":"navi","events":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { playScenario("""{"route":"sky","events":[{"t":0}]}""") }
        assertThrows(IllegalArgumentException::class.java) { playScenario("""{"events":[{"t":0,"gear":"X"}]}""") }
        assertThrows(IllegalArgumentException::class.java) { playScenario("""{"events":[{"t":0,"tbt":{"distance":10}}]}""") }
    }
}
