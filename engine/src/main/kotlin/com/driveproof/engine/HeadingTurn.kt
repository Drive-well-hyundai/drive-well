package com.driveproof.engine

import com.driveproof.model.Consts
import com.driveproof.model.Heading
import kotlin.math.abs
import kotlin.math.sign

internal class HeadingTurn(private val c: Consts) {
    private var last: Heading? = null
    private var direction = 0
    private var pending = 0.0

    fun observe(heading: Heading) {
        val angle = heading.deg?.takeIf { it in 0.0..360.0 }
        if (angle == null) return clear()
        val before = last
        if (before != null && heading.t <= before.t) return
        last = heading
        if (before == null || heading.t - before.t > c.speedGapMs) {
            direction = 0
            pending = 0.0
            return
        }
        val delta = (angle - requireNotNull(before.deg) + 540) % 360 - 180
        val rate = abs(delta) * 1000.0 / (heading.t - before.t)
        if (rate > c.headingMaxDegPerS) return clear()
        val next = if (rate >= c.headingDeadDegPerS) delta.sign.toInt() else 0
        pending = if (next == 0) 0.0 else if (next == direction) pending + delta else delta
        direction = next
    }

    fun consume(at: Long): Pair<Int, Double>? {
        val sample = last ?: return null
        if (at - sample.t > c.speedGapMs) {
            clear()
            return null
        }
        return (direction to pending).also { pending = 0.0 }
    }

    fun clear() {
        last = null
        direction = 0
        pending = 0.0
    }
}
