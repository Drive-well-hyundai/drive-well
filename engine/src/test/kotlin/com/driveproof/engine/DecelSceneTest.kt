package com.driveproof.engine

import com.driveproof.model.BasicKind
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import com.driveproof.model.playScenario
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 회전 앞 미리 줄이기. demo-sim.js와 비교하고(E-4 완료 기준) 골든에 없는 규칙을 본다 */
class DecelSceneTest {

    private val kinds = mapOf("좌회전" to SceneKind.LEFT_TURN, "우회전" to SceneKind.RIGHT_TURN, "유턴" to SceneKind.U_TURN)

    private fun run(route: String, vararg events: String): TripResult =
        Golden.run(TripEngine(), playScenario("""{"route":"$route","events":[${events.joinToString(",")}]}"""))

    @Test
    fun decelScenesMatchReference() {
        for (id in Golden.ids) {
            val want = Golden.result(id).getValue("scenes").jsonArray.map { it.jsonObject }
                .filter { it.getValue("principle").jsonPrimitive.content == "decel" }
            val got = Golden.run(TripEngine(), Golden.signals(id)).scenes.filter { it.principle == Principle.DECEL }
            assertEquals("$id 장면 수", want.size, got.size)
            for ((w, s) in want.zip(got)) {
                val at = "$id t=${s.at}ms"
                val bump = w.getValue("target").jsonPrimitive.content == "bump"
                assertEquals("$at 종류", if (bump) SceneKind.SPEED_BUMP else kinds.getValue(w.getValue("kind").jsonPrimitive.content), s.kind)
                if (bump) {
                    assertEquals("$at 감속 점수", w.getValue("decelS").jsonPrimitive.double, s.decelScore!!, 1e-6)
                    assertEquals("$at 통과 속도 점수", w.getValue("passS").jsonPrimitive.double, s.passScore!!, 1e-6)
                }
                assertEquals("$at 시각", (w.getValue("t").jsonPrimitive.double * 1000).toLong(), s.at)
                assertEquals("$at 추정", w.getValue("est").jsonPrimitive.boolean, s.estimated)
                assertEquals("$at 최대 감속", w.getValue("aMax").jsonPrimitive.double, s.maxDecel!!, 1e-6)
                assertEquals("$at 급감속 기준", w.getValue("thr").jsonPrimitive.double, s.hardDecel!!, 0.0)
                assertEquals("$at 통과 속도", w.getValue("passV").jsonPrimitive.double, s.passKmh!!, 0.0)
                assertEquals("$at 부분 점수", w.getValue("s").jsonPrimitive.double, s.s, 0.01)
                val win = w.getValue("win").jsonArray.map { (it.jsonPrimitive.double * 1000).toLong() }
                assertEquals("$at 구간", win, listOf(s.windowFrom, s.windowTo))
            }
        }
    }

    /** 이슈 완료 기준: 시나리오 1은 최대 감속 약 12km/h/s, 0.30 */
    @Test
    fun scenario1BrakesTwelveKmhPerSecondRightBeforeTheTurn() {
        val s = Golden.run(TripEngine(), Golden.signals("s1")).scenes.single { it.principle == Principle.DECEL }
        assertEquals(12.0, s.maxDecel!!, 0.05)
        assertEquals(15.0, s.hardDecel!!, 0.0) // 구간이 시작될 때 40km/h였다
        assertEquals(0.30, s.s, 0.005)
    }

    @Test
    fun scenesComeInTimeOrderSignalFirst() {
        val scenes = Golden.run(TripEngine(), Golden.signals("s2")).scenes
        assertEquals(listOf(Principle.SIGNAL, Principle.DECEL, Principle.SIGNAL, Principle.DECEL), scenes.map { it.principle })
        assertEquals(scenes.map { it.at }.sorted(), scenes.map { it.at })
    }

    // 54km/h(초당 15m)로 달리다가 회전 바로 앞에서 세게 밟는다. 54에서 14까지 2초, 20km/h/s다
    private val hardBrakeBeforeLeftTurn = arrayOf(
        """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":14000,"speed_kmh":54}""",
        """{"t":80000,"tbt":{"type":"TURN_LEFT","distance":150},"turn_signal":"LEFT"}""",
        """{"t":88000,"speed_kmh":54,"tbt":{"distance":30},"brake":true}""",
        """{"t":90000,"speed_kmh":14,"tbt":{"distance":2},"brake":false}""",
        """{"t":93000,"speed_kmh":30,"tbt":{"type":"TURN_STRAIGHT","distance":500},"turn_signal":"OFF"}""",
        """{"t":150000,"speed_kmh":30}""", """{"t":156000,"speed_kmh":0}""", """{"t":158000,"gear":"P"}""",
    )

    @Test
    fun hardBrakeInsideTheWindowScoresZero() {
        val s = run("navi", *hardBrakeBeforeLeftTurn).scenes.single { it.principle == Principle.DECEL }
        assertEquals(0.0, s.s, 0.0)
        assertEquals(20.0, s.maxDecel!!, 0.5)
    }

    /** 같은 행동을 두 번 깎지 않는다. 구간 안 급감속은 장면에만 반영하고 기본 항목 횟수에서는 뺀다 */
    @Test
    fun hardBrakeInsideTheWindowIsLeftOutOfBasicItems() {
        val basic = run("navi", *hardBrakeBeforeLeftTurn).basic
        assertEquals(listOf(BasicKind.DECEL), basic.map { it.kind })
        assertEquals(Exempt.SCENE, basic[0].exempt)
    }

    @Test
    fun hardBrakeFarFromAnyTurnStaysInBasicItems() {
        val basic = run(
            "navi", """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":14000,"speed_kmh":54}""",
            """{"t":40000,"speed_kmh":54,"brake":true}""", """{"t":42000,"speed_kmh":14,"brake":false}""", """{"t":50000,"speed_kmh":54}""",
            """{"t":150000,"speed_kmh":54}""", """{"t":160000,"speed_kmh":0}""", """{"t":162000,"gear":"P"}""",
        ).basic
        assertEquals(listOf(Exempt.NONE), basic.map { it.exempt })
    }

    @Test
    fun gentleBrakingScoresOne() {
        // 36에서 20까지 4초, 4km/h/s
        val s = run(
            "navi", """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":9000,"speed_kmh":36}""",
            """{"t":80000,"tbt":{"type":"TURN_RIGHT","distance":150},"turn_signal":"RIGHT"}""",
            """{"t":91000,"speed_kmh":36,"tbt":{"distance":40},"brake":true}""",
            """{"t":95000,"speed_kmh":20,"tbt":{"distance":1},"brake":false}""",
            """{"t":98000,"speed_kmh":30,"tbt":{"type":"TURN_STRAIGHT","distance":500},"turn_signal":"OFF"}""",
            """{"t":150000,"speed_kmh":30}""", """{"t":156000,"speed_kmh":0}""", """{"t":158000,"gear":"P"}""",
        ).scenes.single { it.principle == Principle.DECEL }
        assertEquals(1.0, s.s, 0.0)
        assertEquals(20.0, s.passKmh!!, 0.5)
    }

    /** 구간이 시작될 때 30km/h 이하면 급감속 기준은 14다 */
    @Test
    fun thresholdFollowsTheSpeedAtTheStartOfTheWindow() {
        // 28에서 10까지 2초, 9km/h/s. (9 − 5) ÷ (14 − 5) ≈ 0.44가 깎인다
        val s = run(
            "navi", """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":7000,"speed_kmh":28}""",
            """{"t":120000,"tbt":{"type":"TURN_LEFT","distance":150},"turn_signal":"LEFT"}""",
            """{"t":136000,"speed_kmh":28,"tbt":{"distance":20},"brake":true}""",
            """{"t":138000,"speed_kmh":10,"tbt":{"distance":1},"brake":false}""",
            """{"t":141000,"speed_kmh":28,"tbt":{"type":"TURN_STRAIGHT","distance":500},"turn_signal":"OFF"}""",
            """{"t":190000,"speed_kmh":28}""", """{"t":196000,"speed_kmh":0}""", """{"t":198000,"gear":"P"}""",
        ).scenes.single { it.principle == Principle.DECEL }
        assertEquals(14.0, s.hardDecel!!, 0.0)
        assertEquals(1 - (s.maxDecel!! - 5) / 9, s.s, 1e-9)
        assertEquals(9.0, s.maxDecel!!, 0.3)
    }

    /** 추정 회전 구간의 급감속도 주행이 끝나면 장면에만 반영한다 */
    @Test
    fun hardBrakeBeforeAnEstimatedTurnIsExemptAtTheEnd() {
        val r = run(
            "vehicle", """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":14000,"speed_kmh":54}""",
            """{"t":70000,"speed_kmh":54,"brake":true}""", """{"t":72000,"speed_kmh":14,"brake":false}""",
            """{"t":72500,"steering_deg":-320}""", """{"t":76000,"steering_deg":0}""",
            """{"t":80000,"speed_kmh":30}""", """{"t":140000,"speed_kmh":30}""", """{"t":146000,"speed_kmh":0}""", """{"t":148000,"gear":"P"}""",
        )
        assertEquals(listOf(Principle.SIGNAL, Principle.DECEL), r.scenes.map { it.principle })
        assertEquals(0.0, r.scenes[1].s, 0.0)
        assertEquals(listOf(Exempt.SCENE), r.basic.map { it.exempt })
    }
}
