package com.driveproof.scoring

import com.driveproof.model.BasicKind
import com.driveproof.model.CardTopic
import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 6개월 시드(`m6.json`)로 주간 요약, 월 집계, 총점을 계산해 demo-history.js와 비교한다(E-9 완료 기준).
 * 시드는 demo 빌드 에셋을 그대로 읽는다. 날짜는 마지막 주 끝(일요일)에서 며칠 전인지로 적혀 있다.
 */
class SeedSummaryTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val seed = Json.parseToJsonElement(File("../app/src/demo/assets/m6.json").readText()).jsonObject
    private val golden = Json.parseToJsonElement(javaClass.getResource("/m6.json")!!.readText()).jsonObject
    private val end = LocalDate.parse(golden.getValue("endDate").jsonPrimitive.content) // 마지막 주의 일요일
    private val afterEnd = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun num(e: JsonElement?): Double? = if (e == null || e is JsonNull) null else e.jsonPrimitive.double

    private fun noon(daysAgo: Int) = end.minusDays(daysAgo.toLong()).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** 시드 주행을 점수 입력으로 옮긴다. 고칠 점은 골든의 한 장에서 가져온다(앱에서는 저장된 `cardJson`에서 읽는다) */
    private val history: History by lazy {
        val cards = golden.getValue("cards").jsonArray
        val trips = ArrayList<TripRow>()
        val scenes = ArrayList<SceneRow>()
        val basics = ArrayList<BasicRow>()
        seed.getValue("trips").jsonArray.forEachIndexed { i, e ->
            val t = e.jsonObject
            val at = noon(t.getValue("daysAgo").jsonPrimitive.int)
            val fix = cards[i].jsonObject.getValue("fix")
            trips += TripRow(at, t.getValue("km").jsonPrimitive.double, if (fix is JsonNull) null else CardTopic.valueOf(fix.jsonPrimitive.content))
            for (s in t.getValue("scenes").jsonArray.map { it.jsonObject }) {
                val principle = Principle.valueOf(s.getValue("principle").jsonPrimitive.content)
                val value = when (principle) {
                    Principle.SIGNAL -> num(s["onDistanceM"])
                    Principle.DECEL -> num(s["maxDecel"])
                    else -> num(s["maxKmh"])
                }
                scenes += SceneRow(at, principle, s.getValue("s").jsonPrimitive.double, value)
            }
            for (b in t.getValue("basicEvents").jsonArray.map { it.jsonObject }) {
                basics += BasicRow(at, BasicKind.valueOf(b.getValue("kind").jsonPrimitive.content), Exempt.valueOf(b.getValue("exempt").jsonPrimitive.content))
            }
        }
        History(trips, scenes, basics)
    }

    private val weekly: List<WeeklySummary> by lazy {
        weeklySummaries(history, from = noon(181), now = afterEnd, zone = zone, ruleVersion = "2026-11-10")
    }

    private fun near(label: String, want: Double?, got: Double?, eps: Double = 1e-4) {
        if (want == null) assertNull(label, got) else assertEquals(label, want, got!!, eps)
    }

    /** 이슈 완료 기준: 시드로 넣은 기록의 총점이 83점이다 */
    @Test
    fun seedTotalIsEightyThree() {
        val s = score(history, afterEnd - 1)!!
        val want = golden.getValue("score").jsonObject
        near("T", num(want["T"]), s.total)
        near("B", num(want["B"]), s.basic)
        near("S", num(want["S"]), s.situation)
        val p = want.getValue("P").jsonObject
        near("미리 켜기", num(p["signal"]), s.signal.p)
        near("미리 줄이기", num(p["decel"]), s.decel.p)
        near("구역", num(p["zone"]), s.zone.p)
        assertEquals(83, Math.round(s.total))
        assertEquals(golden.getValue("trips").jsonPrimitive.int, s.trips)
        near("주행거리", num(golden["km"]), s.km)
        assertEquals(true, s.qualified) // 873km
    }

    @Test
    fun totalIfLampExemptBrakesWereCounted() {
        near("넣었다면의 총점", num(golden["withoutExemptT"]), scoreCountingLampExempt(history, afterEnd - 1)!!.total)
        assertEquals(true, scoreCountingLampExempt(history, afterEnd - 1)!!.total < score(history, afterEnd - 1)!!.total)
    }

    /** 이슈 완료 기준: 주간 요약 26주가 채워진다 */
    @Test
    fun twentySixWeeksAreFilledAndMatchReference() {
        val want = seed.getValue("weekly").jsonArray.map { it.jsonObject }
        assertEquals(26, weekly.size)
        assertEquals(want.size, weekly.size)
        for ((w, got) in want.zip(weekly)) {
            val at = "주 ${got.weekStart}"
            assertEquals("$at 시작", end.minusDays(w.getValue("daysAgo").jsonPrimitive.int.toLong()), got.weekStart)
            assertEquals("$at 주행 수", w.getValue("trips").jsonPrimitive.int, got.trips)
            near("$at 거리", num(w["km"]), got.km)
            val p = w.getValue("p").jsonObject
            for ((name, c) in listOf("signal" to got.signal, "decel" to got.decel, "zone" to got.zone)) {
                val o = p.getValue(name).jsonObject
                assertEquals("$at $name 기회", o.getValue("n").jsonPrimitive.int, c.n)
                assertEquals("$at $name 성공", o.getValue("ok").jsonPrimitive.int, c.ok)
                near("$at $name 합", num(o["sum"]), c.sum)
            }
            near("$at 켠 거리 평균", num(w["avgOnDistanceM"]), got.avgOnDistanceM)
            near("$at 최대 감속 평균", num(w["avgMaxDecel"]), got.avgMaxDecel)
            near("$at 스쿨존 최고", num(w["maxZoneKmh"]), got.maxZoneKmh)
            assertEquals("$at 깜빡이 없이 돈 회전", w.getValue("missedSignals").jsonPrimitive.int, got.missedSignals)
            val basic = w.getValue("basic").jsonObject
            assertEquals("$at 급감속", basic.getValue("decel").jsonPrimitive.int, got.hardBrakes)
            assertEquals("$at 급가속", basic.getValue("accel").jsonPrimitive.int, got.hardAccels)
            assertEquals("$at 과속", basic.getValue("over").jsonPrimitive.int, got.overs)
            assertEquals("$at 경고등으로 뺀 급감속", w.getValue("exemptTelltale").jsonPrimitive.int, got.exemptTelltale)
            val fix = w.getValue("fix").jsonObject
            assertEquals("$at 고칠 점", CardTopic.entries.associateWith { fix.getValue(it.name).jsonPrimitive.int }, got.fixes)
            near("$at 주 끝 총점", num(w["T"]), got.total)
            near("$at 주 끝 기본 점수", num(w["B"]), got.basic)
            val ps = w.getValue("P").jsonObject
            near("$at P 미리 켜기", num(ps["signal"]), got.pSignal)
            near("$at P 미리 줄이기", num(ps["decel"]), got.pDecel)
            near("$at P 구역", num(ps["zone"]), got.pZone)
            assertEquals("2026-11-10", got.ruleVersion)
        }
        // 장면 구간이라 장면에만 반영한 급감속은 시드에 1번 있다
        assertEquals(1, weekly.sumOf { it.exemptScene })
        assertEquals(3, weekly.sumOf { it.exemptTelltale })
    }

    @Test
    fun weekInProgressIsNotWritten() {
        val wednesday = end.minusDays(4).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val got = weeklySummaries(history, from = noon(181), now = wednesday, zone = zone, ruleVersion = "v")
        assertEquals(25, got.size)
        assertEquals(end.minusDays(13), got.last().weekStart)
        // 일요일 24시가 되는 순간 그 주가 나온다
        assertEquals(26, weeklySummaries(history, noon(181), afterEnd, zone, "v").size)
        assertEquals(25, weeklySummaries(history, noon(181), afterEnd - 1, zone, "v").size)
    }

    @Test
    fun missingWeeksAreFilledFromTheGivenWeekOn() {
        // 마지막으로 쓴 주 다음 월요일부터 채운다
        val from = end.minusDays(20).atStartOfDay(zone).toInstant().toEpochMilli()
        val got = weeklySummaries(history, from, afterEnd, zone, "v")
        assertEquals(listOf(end.minusDays(20), end.minusDays(13), end.minusDays(6)), got.map { it.weekStart })
        assertEquals(weekly.takeLast(3).map { it.total }, got.map { it.total })
    }

    @Test
    fun weekWithoutTripsStillGetsARowWithTheWindowScore() {
        val later = end.plusDays(15).atStartOfDay(zone).toInstant().toEpochMilli()
        val got = weeklySummaries(history, afterEnd, later, zone, "v")
        assertEquals(2, got.size)
        assertEquals(0, got[0].trips)
        assertEquals(0.0, got[0].km, 0.0)
        assertNull(got[0].avgOnDistanceM)
        assertEquals(true, got[0].total != null) // 183일 창 안에 기록이 남아 있다
        assertEquals(emptyList<WeeklySummary>(), weeklySummaries(History(), noon(20), afterEnd, zone, "v").filter { it.total != null })
    }

    /** 월 집계는 PRD를 따른다. 뺀 급감속은 경고등 구간 것만 세고, 달 끝 총점은 그달에 끝난 마지막 주의 값이다 */
    @Test
    fun monthsAreCountedByCalendarMonth() {
        val months = monthSummaries(history, weekly, zone)
        assertEquals(listOf(5, 6, 7, 8, 9, 10, 11), months.map { it.month })
        assertEquals(102, months.sumOf { it.trips })
        assertEquals(score(history, afterEnd - 1)!!.km, months.sumOf { it.km }, 1e-6)
        assertEquals(26, months.sumOf { it.weeks })
        assertEquals(3, months.sumOf { it.exemptTelltale })

        val november = months.last()
        assertEquals(2, november.weeks) // 11월 1일, 11월 8일에 끝난 주
        assertEquals(weekly.last().total, november.total)
        val october = months[5]
        assertEquals(weekly.last { it.weekEnd.monthValue == 10 }.total, october.total)

        // 성공 비율과 기회 수는 그달의 장면만 센다
        val octScenes = history.scenes.filter { java.time.Instant.ofEpochMilli(it.at).atZone(zone).monthValue == 10 }
        assertEquals(octScenes.count { it.principle == Principle.SIGNAL }, october.signal.n)
        assertEquals(octScenes.count { it.principle == Principle.SIGNAL && it.s >= 1.0 }, october.signal.ok)

        // 고칠 점은 그달 주행의 한 장에서 센다
        val fixTotal = CardTopic.entries.associateWith { topic -> months.sumOf { it.fixes.getValue(topic) } }
        assertEquals(CardTopic.entries.associateWith { topic -> history.trips.count { it.fix == topic } }, fixTotal)
    }

    @Test
    fun weekStartIsMonday() {
        val sunday = end.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        assertEquals(end.minusDays(6), weekStartOf(sunday, zone))
        assertEquals(end.plusDays(1), weekStartOf(afterEnd, zone))
    }
}
