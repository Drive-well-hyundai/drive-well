package com.driveproof.service.trip

import com.driveproof.engine.parseSeed
import com.driveproof.engine.seedAnchor
import com.driveproof.engine.seedRecords
import com.driveproof.model.CardTopic
import com.driveproof.model.Exempt
import com.driveproof.scoring.score
import com.driveproof.scoring.scoreCountingLampExempt
import com.driveproof.scoring.weeklySummaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 시드를 넣었을 때 점수와 주간 요약이 어떻게 나오는지 본다(E-9 완료 기준).
 * 저장소(U-1) 없이 시드 → 저장할 기록 → 점수 입력까지를 본다. DAO가 들어오면 같은 기록을 저장한다.
 */
class SeedHistoryTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val seed = parseSeed(File("../app/src/demo/assets/m6.json").readText())

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** 이슈 완료 기준: 시드로 넣은 기록의 총점이 83점이다. 넣는 요일이 달라도 같다 */
    @Test
    fun totalIsEightyThreeWhateverDayItIsImported() {
        // PT 주(2026-11-30 월 ~ 12-06 일)의 하루하루
        for (offset in 0L..6L) {
            val today = LocalDate.of(2026, 11, 30).plusDays(offset)
            val history = historyOf(seedRecords(seed, today, zone))
            val s = score(history, at(today, 10))!!
            assertEquals("$today 총점", 83, Math.round(s.total))
            assertTrue("$today 500km 조건", s.qualified)
        }
    }

    /** 마지막 주 끝에서 보면 demo-history.js의 총점과 같다 */
    @Test
    fun totalAtTheEndOfTheLastWeekMatchesTheReference() {
        val today = LocalDate.of(2026, 12, 3)
        val history = historyOf(seedRecords(seed, today, zone))
        val weekEnd = at(seedAnchor(today), 23)
        val s = score(history, weekEnd)!!
        assertEquals(83.241429, s.total, 1e-4)
        assertEquals(102, s.trips)
        assertEquals(82.470712, scoreCountingLampExempt(history, weekEnd)!!.total, 1e-4)
    }

    /** 이슈 완료 기준: 주간 요약 26주가 채워진다 */
    @Test
    fun twentySixWeeksAreFilled() {
        val today = LocalDate.of(2026, 12, 3)
        val records = seedRecords(seed, today, zone)
        val weekly = weeklySummaries(historyOf(records), from = records.first().endAt, now = at(today, 10), zone = zone, ruleVersion = "2026-11-10")
        assertEquals(26, weekly.size)
        assertEquals(seedAnchor(today).minusDays(6), weekly.last().weekStart)
        assertEquals(102, weekly.sumOf { it.trips })
        assertEquals(83.241429, weekly.last().total!!, 1e-4)
        assertEquals(3, weekly.sumOf { it.exemptTelltale })
        assertEquals(1, weekly.sumOf { it.exemptScene })
        assertTrue(weekly.all { it.total != null })
    }

    @Test
    fun historyRowsCarryBehaviourValuesAndTheFixTopic() {
        val records = seedRecords(seed, LocalDate.of(2026, 12, 3), zone)
        val h = historyOf(records)
        assertEquals(102, h.trips.size)
        assertEquals(records.sumOf { it.scenes.size }, h.scenes.size)
        // 첫 주행: 깜빡이를 8m 전에 켰다. 고칠 점은 미리 켜기
        assertEquals(8.135785, h.scenes.first().value!!, 1e-9)
        assertEquals(CardTopic.SIGNAL, h.trips.first().fix)
        assertEquals(4, h.basics.count { it.exempt != Exempt.NONE })
        assertTrue(h.scenes.all { s -> h.trips.any { it.endAt == s.at } })
    }
}
