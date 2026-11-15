package com.driveproof.scoring

import com.driveproof.model.BasicKind
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 점수 계산. 원본은 scoring.md다 */
class ScoreTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    /** 합이 [sum]이 되게 1점, 나머지 한 장, 0점으로 채운다 */
    private fun scenes(p: Principle, n: Int, sum: Double, at: Long = now): List<SceneRow> {
        val full = sum.toInt()
        val rest = sum - full
        val partial = if (rest > 1e-9) listOf(SceneRow(at, p, rest)) else emptyList()
        return List(full) { SceneRow(at, p, 1.0) } + partial + List(n - full - partial.size) { SceneRow(at, p, 0.0) }
    }

    private fun basics(kind: BasicKind, n: Int, exempt: Exempt = Exempt.NONE) = List(n) { BasicRow(now, kind, exempt) }

    /** 이슈 완료 기준: scoring.md "계산 예"(820km, 기회 50·70·18)가 87.1점이다 */
    @Test
    fun workedExampleIsEightySevenPointOne() {
        val h = History(
            trips = listOf(TripRow(now, 820.0)),
            scenes = scenes(Principle.SIGNAL, 50, 40.5) + scenes(Principle.DECEL, 70, 60.2) + scenes(Principle.ZONE, 18, 18.0),
            basics = basics(BasicKind.DECEL, 9) + basics(BasicKind.ACCEL, 6) + basics(BasicKind.OVER, 4),
        )
        val s = score(h, now)!!
        assertEquals(79.2, s.signal.p!!, 0.05)
        assertEquals(84.0, s.decel.p!!, 0.05)
        assertEquals(89.3, s.zone.p!!, 0.05)
        assertEquals(84.2, s.situation!!, 0.05)
        assertEquals(90.1, s.basicObserved, 0.05)
        assertEquals(90.1, s.basic, 0.05)
        assertEquals(87.1, s.total, 0.05)
        assertEquals(listOf(50, 70, 18), listOf(s.signal.n, s.decel.n, s.zone.n))
        assertTrue(s.qualified)
    }

    /** scoring.md "원칙별 점수 P"의 표 */
    @Test
    fun fewChancesArePulledTowardTheMean() {
        fun p(n: Int, ok: Int) = score(History(listOf(TripRow(now, 10.0)), scenes(Principle.SIGNAL, n, ok.toDouble())), now)!!.signal.p!!
        assertEquals(72.7, p(1, 1), 0.05)
        assertEquals(73.3, p(5, 4), 0.05)
        assertEquals(78.3, p(50, 40), 0.05)
    }

    /** scoring.md "기존 점수와 나란히": 첫 주행 12.3km, 깜빡이 0.5, 감속 1 */
    @Test
    fun sideBySideExample() {
        val scenes = listOf(SceneRow(now, Principle.SIGNAL, 0.5), SceneRow(now, Principle.DECEL, 1.0))
        val trips = listOf(TripRow(now, 12.3))
        val exempt = score(History(trips, scenes, basics(BasicKind.DECEL, 1, Exempt.TELLTALE)), now)!!
        assertEquals(91.1, exempt.basic, 0.05)
        assertEquals(80.8, exempt.total, 0.05)
        val counted = score(History(trips, scenes, basics(BasicKind.DECEL, 1)), now)!!
        assertEquals(59.3, counted.basicObserved, 0.05)
        assertEquals(86.6, counted.basic, 0.05)
        assertEquals(78.5, counted.total, 0.06)
    }

    /** 1.4km에 급감속 1번이면 B_obs는 0, 보정하면 88.8이다(scoring.md "3") */
    @Test
    fun basicScoreIsShrunkByDistance() {
        val s = score(History(listOf(TripRow(now, 1.4)), basics = basics(BasicKind.DECEL, 1)), now)!!
        assertEquals(0.0, s.basicObserved, 0.0)
        assertEquals(88.8, s.basic, 0.05)
    }

    @Test
    fun principleWithoutChancesIsLeftOutOfTheSituationScore() {
        val s = score(History(listOf(TripRow(now, 50.0)), scenes(Principle.SIGNAL, 4, 4.0)), now)!!
        assertNull(s.zone.p)
        assertNull(s.decel.p)
        assertEquals(s.signal.p!!, s.situation!!, 1e-12)
    }

    @Test
    fun noChancesAtAllMeansTotalIsTheBasicScore() {
        val s = score(History(listOf(TripRow(now, 50.0))), now)!!
        assertNull(s.situation)
        assertEquals(s.basic, s.total, 0.0)
    }

    @Test
    fun exemptEventsAndRiskScenesAreNotCounted() {
        val base = History(listOf(TripRow(now, 50.0)), scenes(Principle.SIGNAL, 2, 1.0))
        val more = base + History(
            scenes = listOf(SceneRow(now, Principle.RISK, 0.0)),
            basics = basics(BasicKind.DECEL, 2, Exempt.SCENE) + basics(BasicKind.DECEL, 1, Exempt.TELLTALE),
        )
        assertEquals(score(base, now), score(more, now))
    }

    @Test
    fun recordsOlderThanOneEightyThreeDaysAreLeftOut() {
        val old = now - 184 * day
        val recent = History(listOf(TripRow(now, 100.0)), scenes(Principle.SIGNAL, 3, 3.0))
        val withOld = recent + History(listOf(TripRow(old, 900.0)), scenes(Principle.SIGNAL, 20, 0.0, at = old), listOf(BasicRow(old, BasicKind.DECEL)))
        assertEquals(score(recent, now), score(withOld, now))
        val edge = now - 183 * day
        assertEquals(200.0, score(recent + History(listOf(TripRow(edge, 100.0))), now)!!.km, 0.0)
    }

    @Test
    fun underFiveHundredKmIsStillScoredButNotQualified() {
        val s = score(History(listOf(TripRow(now, 312.0))), now)!!
        assertFalse(s.qualified)
        assertEquals(312.0, s.km, 0.0)
        assertTrue(score(History(listOf(TripRow(now, 500.0))), now)!!.qualified)
    }

    @Test
    fun noDistanceMeansNoScore() {
        assertNull(score(History(), now))
        assertNull(score(History(listOf(TripRow(now - 200 * day, 30.0))), now))
    }

    @Test
    fun basicScoreNeverGoesBelowZero() {
        val s = score(History(listOf(TripRow(now, 1.0)), basics = basics(BasicKind.DECEL, 5)), now)!!
        assertEquals(0.0, s.basicObserved, 0.0)
    }

    private fun golden(id: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/$id.json")!!.readText()).jsonObject

    /** demo-sim.js의 첫 주행 점수와 비교한다. 이슈 완료 기준: 시나리오 1 첫 주행이 78.7점이다 */
    @Test
    fun firstTripScoresMatchReference() {
        val principles = mapOf("signal" to Principle.SIGNAL, "decel" to Principle.DECEL, "zone" to Principle.ZONE)
        val kinds = mapOf("decel" to BasicKind.DECEL, "accel" to BasicKind.ACCEL, "over" to BasicKind.OVER)
        fun num(o: JsonObject, k: String) = o.getValue(k).let { if (it is JsonNull) null else it.jsonPrimitive.double }
        fun near(label: String, want: Double?, got: Double?) {
            if (want == null) assertNull(label, got) else assertEquals(label, want, got!!, 1e-5)
        }
        for (id in listOf("s1", "s2", "s3", "s4", "s5", "b1", "b2", "b3", "b4", "b5", "b6")) {
            val g = golden(id)
            val h = History(
                trips = listOf(TripRow(now, g.getValue("km").jsonPrimitive.double)),
                scenes = g.getValue("scenes").jsonArray.map { it.jsonObject }.map {
                    SceneRow(now, principles.getValue(it.getValue("principle").jsonPrimitive.content), it.getValue("s").jsonPrimitive.double)
                },
                basics = g.getValue("basic").jsonObject.flatMap { (key, list) ->
                    list.jsonArray.map {
                        val exempt = it.jsonObject.getValue("exempt")
                        BasicRow(now, kinds.getValue(key), if (exempt is JsonNull) Exempt.NONE else Exempt.valueOf(exempt.jsonPrimitive.content.uppercase()))
                    }
                },
            )
            val want = g.getValue("score").jsonObject
            val p = want.getValue("P").jsonObject
            val s = score(h, now)!!
            near("$id 미리 켜기", num(p, "signal"), s.signal.p)
            near("$id 미리 줄이기", num(p, "decel"), s.decel.p)
            near("$id 구역", num(p, "zone"), s.zone.p)
            near("$id S", num(want, "S"), s.situation)
            near("$id B", num(want, "B"), s.basic)
            near("$id T", num(want, "T"), s.total)
        }
        assertEquals(78.7, score(h = History(listOf(TripRow(now, 1.427917)), listOf(SceneRow(now, Principle.SIGNAL, 0.5), SceneRow(now, Principle.DECEL, 0.3))), now = now)!!.total, 0.05)
    }
}
