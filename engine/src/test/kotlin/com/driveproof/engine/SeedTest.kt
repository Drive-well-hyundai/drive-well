package com.driveproof.engine

import com.driveproof.model.Exempt
import com.driveproof.model.Principle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 시드 읽기(E-9). 시드는 demo 빌드 에셋 `m6.json`이다 */
class SeedTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val seed = parseSeed(File("../app/src/demo/assets/m6.json").readText())

    private fun date(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    @Test
    fun seedHasOneHundredAndTwoTrips() {
        assertEquals("6개월 누적 기록", seed.name)
        assertEquals(102, seed.trips.size)
        assertEquals(181, seed.trips.maxOf { it.daysAgo })
        assertEquals(0, seed.trips.minOf { it.daysAgo })
        val first = seed.trips.first()
        assertEquals(Principle.SIGNAL, first.scenes[0].principle)
        assertEquals(SceneKind.LEFT_TURN, first.scenes[0].kind)
        assertEquals(8.135785, first.scenes[0].onDistanceM!!, 1e-9)
        assertEquals(15.0, first.scenes[1].hardDecel!!, 0.0)
    }

    @Test
    fun exemptEventsKeepTheirReasonAndMode() {
        val exempts = seed.trips.flatMap { it.exempts }
        assertEquals(listOf("ABS", "ESC", "TURN", "ABS"), exempts.map { it.reason })
        assertEquals(listOf(Exempt.TELLTALE, Exempt.TELLTALE, Exempt.SCENE, Exempt.TELLTALE), exempts.map { it.mode })
        assertEquals(listOf(17.0, 15.0, 14.0, 19.0), exempts.map { it.decel })
        // 뺀 급감속은 기본 항목에도 같은 면제로 들어 있다
        assertEquals(4, seed.trips.sumOf { t -> t.basic.count { it.exempt != Exempt.NONE } })
    }

    /** 마지막 주 끝은 기준일보다 앞선 가장 가까운 일요일이다. 오늘이 일요일이면 지난 일요일이다 */
    @Test
    fun anchorIsTheLastSundayBeforeToday() {
        assertEquals(LocalDate.of(2026, 11, 29), seedAnchor(LocalDate.of(2026, 12, 3)))  // PT 날(목)
        assertEquals(LocalDate.of(2026, 11, 29), seedAnchor(LocalDate.of(2026, 11, 30))) // 월
        assertEquals(LocalDate.of(2026, 11, 29), seedAnchor(LocalDate.of(2026, 12, 5)))  // 토
        assertEquals(LocalDate.of(2026, 11, 29), seedAnchor(LocalDate.of(2026, 12, 6)))  // 일. 이 주는 아직 안 끝났다
        assertEquals(LocalDate.of(2026, 12, 6), seedAnchor(LocalDate.of(2026, 12, 7)))
        assertEquals(DayOfWeek.SUNDAY, seedAnchor(LocalDate.of(2026, 11, 11)).dayOfWeek)
    }

    @Test
    fun recordsAreDatedBackFromTheAnchorOldestFirst() {
        val records = seedRecords(seed, LocalDate.of(2026, 12, 3), zone)
        assertEquals(102, records.size)
        assertEquals(LocalDate.of(2026, 11, 29).minusDays(181), date(records.first().endAt))
        assertEquals(LocalDate.of(2026, 11, 29), date(records.last().endAt))
        assertEquals(records.map { it.endAt }.sorted(), records.map { it.endAt })
        assertEquals("같은 시각의 주행이 없다", records.size, records.map { it.endAt }.distinct().size)
        // 첫 주의 월요일부터 마지막 주의 일요일까지 26주다
        assertEquals(DayOfWeek.MONDAY, date(records.first().endAt).dayOfWeek)
        // 장면과 이벤트의 시각은 주행이 끝난 시각이다
        assertTrue(records.all { r -> r.scenes.all { it.at == r.endAt } && r.basic.all { it.at == r.endAt } })
    }

    /** 한 장은 시드에 없다. 넣을 때 만든 한 장이 demo-history.js의 한 장과 같다 */
    @Test
    fun cardsBuiltOnImportMatchReference() {
        val golden = Json.parseToJsonElement(Golden::class.java.getResource("/m6.json")!!.readText()).jsonObject.getValue("cards").jsonArray
        val records = seedRecords(seed, LocalDate.of(2026, 12, 3), zone)
        records.forEachIndexed { i, r ->
            val want = golden[i].jsonObject.getValue("fix")
            val fix = r.card.lines.firstOrNull { it.kind == LineKind.FIX }?.topic?.name
            assertEquals("주행 $i 고칠 점", if (want is kotlinx.serialization.json.JsonNull) null else want.jsonPrimitive.content, fix)
            assertEquals("주행 $i 줄 수", golden[i].jsonObject.getValue("lines").jsonArray.size, r.card.lines.size)
        }
    }

    @Test
    fun malformedSeedIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { parseSeed("{") }
        assertThrows(IllegalArgumentException::class.java) { parseSeed("""{"kind":"seed"}""") }
        assertThrows(IllegalArgumentException::class.java) { parseSeed("""{"kind":"seed","trips":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { parseSeed("""{"name":"s1","route":"navi","events":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { parseSeed("""{"kind":"seed","trips":[{"daysAgo":-1,"km":1}]}""") }
    }
}
