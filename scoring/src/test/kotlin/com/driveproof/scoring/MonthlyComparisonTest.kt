package com.driveproof.scoring

import com.driveproof.model.Principle
import com.driveproof.model.RULE_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class MonthlyComparisonTest {
    @Test fun appliesNewThresholdsToBothMonthsWithKnownMeasurements() {
        val oct = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli()
        val nov = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
        val rows = listOf(
            SceneRow(oct, Principle.SIGNAL, 1.0, 25.0),
            SceneRow(nov, Principle.SIGNAL, 0.0, 35.0),
            SceneRow(oct, Principle.DECEL, 1.0, 4.0, isBump = true, passKmh = 33.0),
            SceneRow(nov, Principle.DECEL, 0.0, 5.0),
            SceneRow(oct, Principle.ZONE, 1.0, 40.0),
            SceneRow(nov, Principle.ZONE, 1.0, 28.0, limitKmh = 30.0),
        )
        val h = History(listOf(TripRow(oct, 2.0, ruleVersion = "2026-10-01"), TripRow(nov, 2.0, ruleVersion = RULE_VERSION)), rows)
        val comparison = requireNotNull(compareAfterRuleChange(h, YearMonth.of(2026, 11), ZoneOffset.UTC))
        assertEquals(0, comparison.previous.getValue(Principle.SIGNAL).ok)
        assertEquals(1, comparison.current.getValue(Principle.SIGNAL).ok)
        assertEquals(0, comparison.previous.getValue(Principle.DECEL).ok)
        assertEquals(1, comparison.current.getValue(Principle.DECEL).ok)
        assertEquals(setOf(Principle.ZONE), comparison.unavailable)
        assertEquals(rows, h.scenes)
        assertNull(compareAfterRuleChange(h, YearMonth.of(2026, 10), ZoneOffset.UTC))
    }
}
