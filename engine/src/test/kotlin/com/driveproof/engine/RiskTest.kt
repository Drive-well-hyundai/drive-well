package com.driveproof.engine

import com.driveproof.model.CardTopic
import com.driveproof.model.BasicKind
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import com.driveproof.model.playScenario
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 위험 장면과 급감속 면제(E-7). 원본은 driving-record.md "위험 장면"이다 */
class RiskTest {

    private fun run(vararg events: String): TripResult =
        Golden.run(TripEngine(), playScenario("""{"route":"navi","events":[${events.joinToString(",")}]}"""))

    @Test
    fun risksMatchReference() {
        for (id in Golden.ids) {
            val want = Golden.result(id).getValue("risks").jsonArray.map { it.jsonObject }
            val got = Golden.run(TripEngine(), Golden.signals(id)).risks
            assertEquals("$id 위험 장면 수", want.size, got.size)
            for ((w, r) in want.zip(got)) {
                assertEquals("$id 경고등", w.getValue("type").jsonPrimitive.content, r.lamp)
                assertEquals("$id 시각", (w.getValue("t").jsonPrimitive.double * 1000).toLong(), r.at)
                assertEquals("$id 앞뒤 5초 최대 감속", w.getValue("aMax").jsonPrimitive.double, r.maxDecel, 1e-6)
                assertEquals("$id 5초 전 속도", w.getValue("vBefore").jsonPrimitive.double, r.kmhBefore, 0.0)
            }
        }
    }

    /** 이슈 완료 기준: 시나리오 5의 급감속 약 17km/h/s가 기본 항목에서 빠진다 */
    @Test
    fun scenario5HardBrakeIsLeftOutBecauseOfTheAbsLamp() {
        val r = Golden.run(TripEngine(), Golden.signals("s5"))
        val brake = r.basic.single()
        assertEquals(BasicKind.DECEL, brake.kind)
        assertEquals(17.3, brake.value, 0.05)
        assertEquals(Exempt.TELLTALE, brake.exempt)
        assertEquals(listOf(brake), r.exempts)
        val risk = r.risks.single()
        assertEquals("ABS", risk.lamp)
        assertTrue(risk.exempts)
        assertTrue(risk.brake)
        // 한 장에는 기본 항목이 나오지 않는다. 회전 장면만 본다
        assertEquals(listOf(CardTopic.SIGNAL, CardTopic.DECEL), r.card.lines.map { it.topic })
    }

    // 54km/h로 달리다 40초에 2초 동안 14km/h까지 세게 밟는다
    private val start = arrayOf("""{"t":0,"gear":"D","speed_kmh":0}""", """{"t":14000,"speed_kmh":54}""")
    private val brake = arrayOf("""{"t":40000,"speed_kmh":54,"brake":true}""", """{"t":42000,"speed_kmh":14,"brake":false}""", """{"t":50000,"speed_kmh":54}""")
    private val finish = arrayOf("""{"t":150000,"speed_kmh":54}""", """{"t":160000,"speed_kmh":0}""", """{"t":162000,"gear":"P"}""")

    /** 골든 경계: 주행 시작 때 이미 켜져 있던 경고등은 면제가 없다 */
    @Test
    fun lampAlreadyOnAtTheStartDoesNotExempt() {
        val r = run("""{"t":0,"gear":"D","speed_kmh":0,"telltale":{"ABS":"ON"}}""", """{"t":14000,"speed_kmh":54}""", *brake, *finish)
        assertEquals(emptyList<RiskRec>(), r.risks)
        assertEquals(listOf(Exempt.NONE), r.basic.map { it.exempt })
    }

    /** 실차에서는 등록 직후 원래 상태가 조금 늦게 올 수 있다. 처음 5초 안에 본 상태는 원래 상태로 본다 */
    @Test
    fun lampFirstSeenRightAfterTheStreamStartsIsTheBaseline() {
        val r = run("""{"t":0,"gear":"D","speed_kmh":0}""", """{"t":2000,"telltale":{"ESC":"ON"}}""", """{"t":14000,"speed_kmh":54}""", *brake, *finish)
        assertEquals(emptyList<RiskRec>(), r.risks)
    }

    @Test
    fun lampTurningOnWithinFiveSecondsAfterTheBrakeExempts() {
        // 급감속이 40초대에 시작하고 경고등이 44.5초에 켜진다
        val r = run(*start, *brake, """{"t":44500,"telltale":{"ESC":"BLINK"}}""", """{"t":46000,"telltale":{"ESC":"OFF"}}""", *finish)
        assertEquals(listOf("ESC"), r.risks.map { it.lamp })
        assertEquals(listOf(Exempt.TELLTALE), r.basic.map { it.exempt })
    }

    @Test
    fun lampMoreThanFiveSecondsAwayDoesNotExempt() {
        val r = run(*start, *brake, """{"t":47000,"telltale":{"FCA":"ON"}}""", *finish)
        assertEquals(listOf("FCA"), r.risks.map { it.lamp })
        assertEquals(listOf(Exempt.NONE), r.basic.map { it.exempt })
    }

    @Test
    fun driverAttentionLampIsRecordedButDoesNotExempt() {
        val r = run(*start, """{"t":39500,"telltale":{"DRIVER_ATTENTION":"ON"}}""", *brake, *finish)
        assertEquals(listOf(false), r.risks.map { it.exempts })
        assertEquals(listOf(Exempt.NONE), r.basic.map { it.exempt })
    }

    @Test
    fun lampTurningOnAgainIsANewRisk() {
        val r = run(
            *start, """{"t":30000,"telltale":{"ABS":"ON"}}""", """{"t":32000,"telltale":{"ABS":"OFF"}}""",
            """{"t":60000,"telltale":{"ABS":"BLINK"}}""", """{"t":61000,"telltale":{"ABS":"ON"}}""", *finish,
        )
        // 깜빡임에서 켜짐으로 바뀐 것은 새 위험 장면이 아니다
        assertEquals(listOf(30_000L, 60_000L), r.risks.map { it.at })
    }

    @Test
    fun unknownLampsAreIgnored() {
        assertEquals(emptyList<RiskRec>(), run(*start, """{"t":30000,"telltale":{"TPMS":"ON"}}""", *finish).risks)
    }

    /** 경고등 구간과 겹치는 미리 줄이기 장면은 판정하지 않는다. 미리 켜기 장면은 남는다 */
    @Test
    fun decelSceneOverlappingTheLampWindowIsNotJudged() {
        val r = run(
            *start,
            """{"t":80000,"tbt":{"type":"TURN_LEFT","distance":150},"turn_signal":"LEFT"}""",
            """{"t":88000,"speed_kmh":54,"tbt":{"distance":30},"brake":true}""", """{"t":88500,"telltale":{"ABS":"ON"}}""",
            """{"t":90000,"speed_kmh":14,"tbt":{"distance":2},"brake":false}""", """{"t":91000,"telltale":{"ABS":"OFF"}}""",
            """{"t":93000,"speed_kmh":30,"tbt":{"type":"TURN_STRAIGHT","distance":500},"turn_signal":"OFF"}""",
            """{"t":150000,"speed_kmh":30}""", """{"t":156000,"speed_kmh":0}""", """{"t":158000,"gear":"P"}""",
        )
        assertEquals(listOf(Principle.SIGNAL), r.scenes.map { it.principle })
        // 급감속은 장면 구간 안이기도 하지만 경고등 면제가 먼저다
        assertEquals(listOf(Exempt.TELLTALE), r.basic.map { it.exempt })
        assertEquals(20.0, r.risks.single().maxDecel, 0.5)
    }

    @Test
    fun confirmedGivesTheRiskAndTheExemptBrakeAfterFiveSeconds() {
        val engine = TripEngine()
        val signals = Golden.signals("s5")
        signals.filter { it.t <= 70_000 }.forEach(engine::onSignal)
        val got = engine.confirmed()
        assertEquals(listOf(Exempt.TELLTALE), got.filterIsInstance<BasicRec>().map { it.exempt })
        assertEquals(listOf("ABS"), got.filterIsInstance<RiskRec>().map { it.lamp })
        assertEquals(emptyList<Confirmed>(), engine.confirmed())
    }
}
