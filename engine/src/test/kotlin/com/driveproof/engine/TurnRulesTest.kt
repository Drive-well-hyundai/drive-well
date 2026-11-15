package com.driveproof.engine

import com.driveproof.model.Principle
import com.driveproof.model.Blink
import com.driveproof.model.Consts
import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.Signal
import com.driveproof.model.Speed
import com.driveproof.model.Tbt
import com.driveproof.model.TurnSignal
import com.driveproof.model.TurnType
import com.driveproof.model.playScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 골든 시나리오에 없는 회전 규칙. 원본은 driving-record.md "두 경로", "요각 추정", "회전 통과", "미리 켜기"다 */
class TurnRulesTest {

    private fun scenes(route: String, vararg events: String): List<SceneRec> {
        val json = """{"route":"$route","events":[${events.joinToString(",")}]}"""
        return Golden.run(TripEngine(), playScenario(json)).scenes.filter { it.principle == Principle.SIGNAL }
    }

    // 30km/h로 600m쯤 달린 뒤 72초부터 회전하고, 다시 400m쯤 달려 주차한다
    private val approach = arrayOf("""{"t":0,"gear":"D","speed_kmh":0}""", """{"t":8000,"speed_kmh":30}""", """{"t":72000,"speed_kmh":30}""", """{"t":74000,"speed_kmh":20}""")
    private val leave = arrayOf("""{"t":82000,"speed_kmh":30}""", """{"t":130000,"speed_kmh":30}""", """{"t":136000,"speed_kmh":0}""", """{"t":138000,"gear":"P"}""")

    @Test
    fun steeringAndSpeedFindARightTurn() {
        val s = scenes("vehicle", *approach, """{"t":76000,"steering_deg":320}""", """{"t":78500,"steering_deg":0}""", *leave).single()
        assertEquals(SceneKind.RIGHT_TURN, s.kind)
        assertEquals(ScenePath.VEHICLE, s.path)
        assertEquals(true, s.estimated)
        assertNull(s.onDistanceM)
    }

    @Test
    fun signalDistanceIsDrivenDistanceSinceItWasTurnedOn() {
        // 74초에 켜고 20km/h(초당 5.56m)로 2초 달려 76초에 돌기 시작한다
        val s = scenes("vehicle", *approach, """{"t":74000,"turn_signal":"LEFT"}""", """{"t":76000,"steering_deg":-320}""", """{"t":78500,"steering_deg":0}""", *leave).single()
        assertEquals(SceneKind.LEFT_TURN, s.kind)
        assertEquals(11.1, s.onDistanceM!!, 1.5)
    }

    @Test
    fun oppositeSignalCountsAsNone() {
        val s = scenes("vehicle", *approach, """{"t":70000,"turn_signal":"RIGHT"}""", """{"t":76000,"steering_deg":-320}""", """{"t":78500,"steering_deg":0}""", *leave).single()
        assertEquals(SceneKind.LEFT_TURN, s.kind)
        assertNull(s.onDistanceM)
        assertEquals(0.0, s.s, 0.0)
    }

    @Test
    fun overOneSixtyDegreesIsAUTurn() {
        // 핸들 −450도, 15km/h로 4초. 초당 약 55도라 220도쯤 돈다
        val s = scenes(
            "vehicle", *approach, """{"t":75000,"speed_kmh":15}""",
            """{"t":76000,"steering_deg":-450}""", """{"t":80000,"steering_deg":0,"speed_kmh":15}""", *leave,
        ).single()
        assertEquals(SceneKind.U_TURN, s.kind)
    }

    @Test
    fun reverseGearIsIgnored() {
        assertEquals(
            emptyList<SceneRec>(),
            scenes("vehicle", *approach, """{"t":75500,"gear":"R"}""", """{"t":76000,"steering_deg":320}""", """{"t":78500,"steering_deg":0}""", """{"t":79000,"gear":"D"}""", *leave),
        )
    }

    @Test
    fun bendAboveFortyKmhIsIgnored() {
        // 45km/h, 핸들 100도로 3초. 초당 약 33도라 99도쯤 돌지만 굽은 길이다
        assertEquals(
            emptyList<SceneRec>(),
            scenes(
                "vehicle", """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":12000,"speed_kmh":45}""",
                """{"t":60000,"steering_deg":100}""", """{"t":63000,"steering_deg":0}""",
                """{"t":100000,"speed_kmh":45}""", """{"t":110000,"speed_kmh":0}""", """{"t":112000,"gear":"P"}""",
            ),
        )
    }

    @Test
    fun gentleCurveIsIgnored() {
        // 30km/h, 핸들 40도로 9초. 초당 약 8.7도라 78도쯤 돌지만 완만한 커브다
        assertEquals(
            emptyList<SceneRec>(),
            scenes(
                "vehicle", """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":8000,"speed_kmh":30}""",
                """{"t":60000,"steering_deg":40}""", """{"t":69000,"steering_deg":0}""",
                """{"t":120000,"speed_kmh":30}""", """{"t":126000,"speed_kmh":0}""", """{"t":128000,"gear":"P"}""",
            ),
        )
    }

    /** 기준 구현은 핸들이 반대로 넘어가는 순간의 앞 구간만 버린다. 뒤 구간은 따로 본다 */
    @Test
    fun firstHalfOfAnSCurveIsDropped() {
        val got = scenes(
            "vehicle", *approach, """{"t":76000,"steering_deg":320}""", """{"t":78500,"steering_deg":-320}""", """{"t":81000,"steering_deg":0}""",
            """{"t":84000,"speed_kmh":30}""", """{"t":130000,"speed_kmh":30}""", """{"t":136000,"speed_kmh":0}""", """{"t":138000,"gear":"P"}""",
        )
        assertEquals(listOf(SceneKind.LEFT_TURN), got.map { it.kind })
    }

    @Test
    fun turnInTheFirstTwoHundredMetresIsDropped() {
        // 20km/h로 20초쯤, 100m 남짓에서 돈다
        assertEquals(
            emptyList<SceneRec>(),
            scenes(
                "vehicle", """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":5000,"speed_kmh":20}""",
                """{"t":20000,"steering_deg":320}""", """{"t":22500,"steering_deg":0}""",
                """{"t":30000,"speed_kmh":30}""", """{"t":160000,"speed_kmh":30}""", """{"t":166000,"speed_kmh":0}""", """{"t":168000,"gear":"P"}""",
            ),
        )
    }

    @Test
    fun turnInTheLastTwoHundredMetresIsDropped() {
        // 회전 뒤 100m쯤 달리고 주차한다
        assertEquals(
            emptyList<SceneRec>(),
            scenes(
                "vehicle", """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":8000,"speed_kmh":30}""", """{"t":130000,"speed_kmh":30}""", """{"t":132000,"speed_kmh":20}""",
                """{"t":134000,"steering_deg":320}""", """{"t":136500,"steering_deg":0}""",
                """{"t":150000,"speed_kmh":20}""", """{"t":154000,"speed_kmh":0}""", """{"t":156000,"gear":"P"}""",
            ),
        )
    }

    @Test
    fun secondEstimatedTurnWithinFiftyMetresIsDropped() {
        // 우회전하고 3초(약 17m) 뒤 좌회전한다. 앞 회전만 남는다
        val got = scenes(
            "vehicle", *approach, """{"t":76000,"steering_deg":320}""", """{"t":78500,"steering_deg":0}""",
            """{"t":81500,"steering_deg":-320,"speed_kmh":20}""", """{"t":84000,"steering_deg":0}""",
            """{"t":88000,"speed_kmh":30}""", """{"t":130000,"speed_kmh":30}""", """{"t":136000,"speed_kmh":0}""", """{"t":138000,"gear":"P"}""",
        )
        assertEquals(listOf(SceneKind.RIGHT_TURN), got.map { it.kind })
    }

    // 내비 경로. 36km/h(초당 10m)로 달린다
    private val cruise = arrayOf("""{"t":0,"gear":"D","speed_kmh":0}""", """{"t":9000,"speed_kmh":36}""")
    private val park = arrayOf("""{"t":160000,"speed_kmh":36}""", """{"t":168000,"speed_kmh":0}""", """{"t":170000,"gear":"P"}""")

    @Test
    fun oppositeSignalOnANaviTurnCountsAsNone() {
        val s = scenes(
            "navi", *cruise, """{"t":60000,"tbt":{"type":"TURN_LEFT","distance":150}}""", """{"t":70000,"tbt":{"distance":50},"turn_signal":"RIGHT"}""",
            """{"t":75000,"tbt":{"distance":0}}""", """{"t":76000,"tbt":{"type":"TURN_STRAIGHT","distance":500},"turn_signal":"OFF"}""", *park,
        ).single()
        assertEquals(SceneKind.LEFT_TURN, s.kind)
        assertNull(s.onDistanceM)
    }

    /** 앞 회전부터 깜빡이를 켜 둔 채 같은 방향으로 이어 돈다. 안내가 시작될 때의 남은 거리(최대 200m)가 켠 거리다 */
    @Test
    fun signalAlreadyOnWhenGuidanceStartsUsesTheRemainingDistanceCappedAtTwoHundred() {
        val got = scenes(
            "navi", *cruise, """{"t":50000,"turn_signal":"LEFT"}""",
            """{"t":60000,"tbt":{"type":"TURN_LEFT","distance":300}}""", """{"t":90000,"tbt":{"distance":0}}""",
            """{"t":90100,"tbt":{"type":"TURN_SHARP_L","distance":120}}""", """{"t":102100,"tbt":{"distance":0}}""",
            """{"t":103000,"tbt":{"type":"TURN_STRAIGHT","distance":500},"turn_signal":"OFF"}""", *park,
        )
        assertEquals(listOf(200.0, 120.0), got.map { it.onDistanceM })
        assertEquals(listOf(1.0, 1.0), got.map { it.s })
    }

    /** 갱신이 드물어 회전 바로 앞 값을 못 받았다. 통과 시점을 믿을 수 없다 */
    @Test
    fun turnWhoseClosestValueIsOverFiftyMetresIsDropped() {
        assertEquals(
            emptyList<SceneRec>(),
            scenes(
                "navi", *cruise, """{"t":60000,"tbt":{"type":"TURN_LEFT","distance":150},"turn_signal":"LEFT"}""", """{"t":69000,"tbt":{"distance":60}}""",
                """{"t":76000,"tbt":{"type":"TURN_STRAIGHT","distance":500},"turn_signal":"OFF"}""", *park,
            ),
        )
    }

    @Test
    fun sameKindOfTurnTwiceInARowIsTwoScenes() {
        val got = scenes(
            "navi", *cruise, """{"t":60000,"tbt":{"type":"TURN_RIGHT","distance":150}}""", """{"t":72000,"tbt":{"distance":30},"turn_signal":"RIGHT"}""",
            """{"t":75000,"tbt":{"distance":0}}""", """{"t":75100,"tbt":{"type":"TURN_STRAIGHT","distance":1},"turn_signal":"OFF"}""",
            """{"t":75200,"tbt":{"type":"TURN_RIGHT","distance":200}}""", """{"t":95200,"tbt":{"distance":0}}""",
            """{"t":96000,"tbt":{"type":"TURN_STRAIGHT","distance":500}}""", *park,
        )
        assertEquals(listOf(30.0, null), got.map { it.onDistanceM })
    }

    @Test
    fun uTurnGuidanceExpectsTheLeftSignal() {
        val s = scenes(
            "navi", *cruise, """{"t":60000,"tbt":{"type":"TURN_UTURN","distance":150}}""", """{"t":71000,"tbt":{"distance":40},"turn_signal":"LEFT"}""",
            """{"t":75000,"tbt":{"distance":0}}""", """{"t":76000,"tbt":{"type":"TURN_STRAIGHT","distance":500},"turn_signal":"OFF"}""", *park,
        ).single()
        assertEquals(SceneKind.U_TURN, s.kind)
        assertEquals(40.0, s.onDistanceM!!, 1e-6)
    }

    /**
     * 실차에서는 남은 거리가 드물게 갱신될 수 있다(갱신 주기 미실측).
     * 갱신 사이에 켠 깜빡이는 마지막 갱신값에서 그 뒤 달린 거리를 뺀 값을 켠 거리로 쓴다.
     */
    @Test
    fun signalBetweenSparseUpdatesIsInterpolatedWithDrivenDistance() {
        val engine = TripEngine(Consts(minKm = 0.0))
        val signals = ArrayList<Signal>()
        signals += Gear(0, GearPos.D)
        for (t in 0L..40_000L step 100) {
            when (t) {
                20_000L -> signals += Tbt(t, TurnType.TURN_LEFT, 100.0)
                25_000L -> signals += Tbt(t, TurnType.TURN_LEFT, 50.0)
                27_000L -> signals += TurnSignal(t, Blink.LEFT) // 50m 갱신 뒤 2초, 20m를 더 왔다
                29_500L -> signals += Tbt(t, TurnType.TURN_LEFT, 5.0)
                31_000L -> signals += Tbt(t, TurnType.TURN_STRAIGHT, 300.0)
            }
            signals += Speed(t, 36.0)
        }
        signals.forEach(engine::onSignal)
        val s = engine.end(40_000).scenes.single { it.principle == Principle.SIGNAL }
        assertEquals(30.0, s.onDistanceM!!, 1.1)
        assertEquals(29_500L, s.at) // 통과 시각은 최솟값을 받은 시각이다
    }

    @Test
    fun tripShorterThanTheMinimumHasNoScenes() {
        val engine = TripEngine(Consts(minKm = 1.0))
        val json = """{"route":"navi","events":[{"t":0,"gear":"D","speed_kmh":0},{"t":5000,"speed_kmh":36},
            {"t":10000,"tbt":{"type":"TURN_LEFT","distance":100},"turn_signal":"LEFT"},{"t":20000,"tbt":{"distance":0}},
            {"t":21000,"tbt":{"type":"TURN_STRAIGHT","distance":500},"turn_signal":"OFF"},
            {"t":40000,"speed_kmh":36},{"t":45000,"speed_kmh":0},{"t":46000,"gear":"P"}]}"""
        val r = Golden.run(engine, playScenario(json))
        assertEquals(0.4, r.km, 0.05)
        assertEquals(emptyList<SceneRec>(), r.scenes)
    }

    @Test
    fun naviScenesAreConfirmedFiveSecondsAfterThePass() {
        val engine = TripEngine()
        val signals = Golden.signals("s1")
        signals.filter { it.t <= 106_000 }.forEach(engine::onSignal)
        assertEquals(emptyList<Confirmed>(), engine.confirmed()) // 통과는 102.0초다
        signals.filter { it.t in 106_001..108_000 }.forEach(engine::onSignal)
        assertEquals(listOf(Principle.SIGNAL, Principle.DECEL), engine.confirmed().map { (it as SceneRec).principle })
        assertEquals(emptyList<Confirmed>(), engine.confirmed())
    }
}
