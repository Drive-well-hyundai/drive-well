package com.driveproof.engine

import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import com.driveproof.model.playScenario
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 과속방지턱과 스쿨존 판정(E-8). 원본은 driving-record.md "미리 줄이기", "구역 지키기"다 */
class CautionSceneTest {

    private fun run(vararg events: String): TripResult =
        Golden.run(TripEngine(), playScenario("""{"route":"navi","events":[${events.joinToString(",")}]}"""))

    /** 장면 전체의 순서와 원칙이 demo-sim.js와 같다 */
    @Test
    fun allScenesComeInTheReferenceOrder() {
        for (id in Golden.ids) {
            val want = Golden.result(id).getValue("scenes").jsonArray.map { it.jsonObject }
            val got = Golden.run(TripEngine(), Golden.signals(id)).scenes
            assertEquals("$id 원칙 순서", want.map { it.getValue("principle").jsonPrimitive.content.uppercase() }, got.map { it.principle.name })
            assertEquals("$id 시각", want.map { (it.getValue("t").jsonPrimitive.double * 1000).toLong() }, got.map { it.at })
            for ((w, s) in want.zip(got)) assertEquals("$id t=${s.at} 부분 점수", w.getValue("s").jsonPrimitive.double, s.s, 0.01)
        }
    }

    @Test
    fun zoneScenesMatchReference() {
        for (id in Golden.ids) {
            val want = Golden.result(id).getValue("scenes").jsonArray.map { it.jsonObject }.filter { it.getValue("principle").jsonPrimitive.content == "zone" }
            val got = Golden.run(TripEngine(), Golden.signals(id)).scenes.filter { it.principle == Principle.ZONE }
            assertEquals("$id 스쿨존 수", want.size, got.size)
            for ((w, s) in want.zip(got)) {
                assertEquals("$id 최고 속도", w.getValue("max").jsonPrimitive.double, s.maxKmh!!, 0.0)
                assertEquals("$id 제한속도", w.getValue("limit").jsonPrimitive.int, s.limitKmh)
                assertEquals("$id 부분 점수", w.getValue("s").jsonPrimitive.double, s.s, 1e-6)
            }
        }
    }

    /** 이슈 완료 기준: 시나리오 3은 스쿨존 37km/h, 0.65 */
    @Test
    fun scenario3SchoolZoneAtThirtySeven() {
        val z = Golden.run(TripEngine(), Golden.signals("s3")).scenes.single { it.principle == Principle.ZONE }
        assertEquals(SceneKind.SCHOOL_ZONE, z.kind)
        assertEquals(37.0, z.maxKmh!!, 0.0)
        assertEquals(30, z.limitKmh)
        assertEquals(0.65, z.s, 1e-9)
    }

    /** 이슈 완료 기준: 시나리오 4는 과속방지턱 1.00 */
    @Test
    fun scenario4SpeedBumpScoresOne() {
        val b = Golden.run(TripEngine(), Golden.signals("s4")).scenes.single { it.kind == SceneKind.SPEED_BUMP }
        assertEquals(Principle.DECEL, b.principle)
        assertEquals(1.0, b.s, 0.0)
        assertEquals(16.0, b.passKmh!!, 0.0)
        assertEquals(4.0, b.maxDecel!!, 0.05)
    }

    private val start = arrayOf("""{"t":0,"gear":"D","speed_kmh":0}""", """{"t":10000,"speed_kmh":40}""")
    private val finish = arrayOf("""{"t":150000,"speed_kmh":40}""", """{"t":160000,"speed_kmh":0}""", """{"t":162000,"gear":"P"}""")

    @Test
    fun speedBumpTakenAtFortyScoresHalfOnPassSpeed() {
        val b = run(*start, """{"t":30000,"caution":{"type":"SPEED_BUMP","remain":111}}""", """{"t":40000,"caution":{"remain":0}}""", """{"t":42000,"caution":null}""", *finish)
            .scenes.single()
        assertEquals(1.0, b.decelScore!!, 0.0)
        assertEquals(0.5, b.passScore!!, 1e-9)
        assertEquals(0.5, b.s, 1e-9)
    }

    @Test
    fun hardBrakeBeforeTheBumpIsScoredInTheSceneOnly() {
        // 54에서 14까지 2초, 20km/h/s로 줄이고 넘는다
        val r = run(
            """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":14000,"speed_kmh":54}""",
            """{"t":30000,"caution":{"type":"SPEED_BUMP","remain":120}}""", """{"t":38000,"speed_kmh":54,"caution":{"remain":18},"brake":true}""",
            """{"t":40000,"speed_kmh":14,"caution":{"remain":0},"brake":false}""", """{"t":42000,"caution":null}""", """{"t":50000,"speed_kmh":40}""", *finish,
        )
        val b = r.scenes.single()
        assertEquals(0.0, b.decelScore!!, 0.0)
        assertEquals(1.0, b.passScore!!, 0.0)
        assertEquals(0.0, b.s, 0.0)
        assertEquals(listOf(Exempt.SCENE), r.basic.map { it.exempt })
    }

    @Test
    fun eachSpeedBumpIsJudgedOnce() {
        val r = run(
            *start, """{"t":30000,"caution":{"type":"SPEED_BUMP","remain":111}}""", """{"t":40000,"caution":{"remain":0}}""", """{"t":44000,"caution":{"remain":0}}""",
            """{"t":44100,"caution":null}""", """{"t":60000,"caution":{"type":"SPEED_BUMP","remain":111}}""", """{"t":70000,"caution":{"remain":0}}""", """{"t":71000,"caution":null}""", *finish,
        )
        assertEquals(listOf(40_000L, 70_000L), r.scenes.map { it.at })
    }

    @Test
    fun schoolZoneUsesThirtyWhenTheLimitIsUnknown() {
        val z = run(*start, """{"t":30000,"caution":{"type":"SCHOOL_ZONE","remain":111}}""", """{"t":40000,"caution":{"remain":0}}""", """{"t":80000,"caution":null}""", *finish)
            .scenes.single()
        assertEquals(30, z.limitKmh)
        assertEquals(0.5, z.s, 1e-9) // 40km/h, 제한 30
    }

    @Test
    fun schoolZoneFiftyOrMoreScoresZeroAndUnderTheLimitScoresOne() {
        fun zoneAt(kmh: Int, limit: Int) = run(
            """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":15000,"speed_kmh":$kmh}""",
            """{"t":40000,"caution":{"type":"SCHOOL_ZONE","remain":0,"limit":$limit}}""", """{"t":100000,"caution":null}""",
            """{"t":300000,"speed_kmh":$kmh}""", """{"t":310000,"speed_kmh":0}""", """{"t":312000,"gear":"P"}""",
        ).scenes.single().s
        assertEquals(0.0, zoneAt(52, 30), 0.0)
        assertEquals(1.0, zoneAt(29, 30), 0.0)
        assertEquals(1.0, zoneAt(48, 50), 0.0)
    }

    /** 스쿨존 2곳 (implementation.md 경계 시나리오). 곳마다 판정한다 */
    @Test
    fun twoSchoolZonesAreTwoScenes() {
        val r = run(
            """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":9000,"speed_kmh":36}""",
            """{"t":30000,"speed_kmh":36,"caution":{"type":"SCHOOL_ZONE","remain":0,"limit":30}}""", """{"t":34000,"speed_kmh":28}""",
            """{"t":80000,"caution":null}""",
            """{"t":100000,"speed_kmh":28,"caution":{"type":"SCHOOL_ZONE","remain":0,"limit":30}}""", """{"t":104000,"speed_kmh":41}""",
            """{"t":160000,"caution":null}""", """{"t":200000,"speed_kmh":41}""", """{"t":210000,"speed_kmh":0}""", """{"t":212000,"gear":"P"}""",
        )
        assertEquals(listOf(36.0, 41.0), r.scenes.map { it.maxKmh })
        assertEquals(listOf(0.7, 0.45), r.scenes.map { Math.round(it.s * 100) / 100.0 })
    }

    @Test
    fun finishedZoneIsNotJudgedAgainWhileTheSameGuidanceGoesOn() {
        // 안내가 0m로 60초 동안 남아 있다. 300m를 지난 뒤에도 같은 스쿨존이다
        val r = run(*start, """{"t":30000,"caution":{"type":"SCHOOL_ZONE","remain":0,"limit":30}}""", """{"t":90000,"caution":null}""", *finish)
        assertEquals(1, r.scenes.size)
    }

    @Test
    fun parkingInsideTheZoneStillJudgesIt() {
        val r = run(
            """{"t":0,"gear":"D","speed_kmh":0}""", """{"t":9000,"speed_kmh":36}""", """{"t":120000,"speed_kmh":36,"caution":{"type":"SCHOOL_ZONE","remain":0,"limit":30}}""",
            """{"t":126000,"speed_kmh":0}""", """{"t":128000,"gear":"P"}""",
        )
        assertEquals(listOf(Principle.ZONE), r.scenes.map { it.principle })
        assertEquals(36.0, r.scenes[0].maxKmh!!, 0.0)
    }

    @Test
    fun otherCautionPointsAreIgnored() {
        assertEquals(emptyList<SceneRec>(), run(*start, """{"t":30000,"caution":{"type":"ACCIDENT_AREA","remain":0}}""", """{"t":40000,"caution":null}""", *finish).scenes)
    }
}
