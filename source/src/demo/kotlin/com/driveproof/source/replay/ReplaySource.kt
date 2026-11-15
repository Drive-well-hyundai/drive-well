package com.driveproof.source.replay

import android.os.Handler
import android.os.SystemClock
import com.driveproof.model.Signal
import com.driveproof.source.SignalSink
import com.driveproof.source.SignalThread
import com.driveproof.source.SourceKind
import com.driveproof.source.VehicleDataSource

/**
 * 시나리오 재생기(E-1)가 만든 신호를 시나리오 시각에 맞춰 dp-signal 스레드에서 내보낸다.
 * 신호의 `t`는 시나리오 시작부터의 시각 그대로 둔다(implementation.md "스레드와 통지").
 *
 * @param signals 재생기 출력. 0.1초 격자에 맞춘 시각순 목록이다
 * @param onFinished 마지막 묶음을 내보낸 뒤 dp-signal 스레드에서 부른다
 */
class ReplaySource(
    signals: List<Signal>,
    private val handler: Handler = SignalThread.handler,
    private val onFinished: () -> Unit = {},
) : VehicleDataSource {

    override val kind = SourceKind.REPLAY

    private val ticks = ticksOf(signals)
    private val token = Any()

    val durationMs: Long get() = ticks.lastOrNull()?.t ?: 0L

    override fun start(sink: SignalSink) {
        handler.removeCallbacksAndMessages(token)
        if (ticks.isEmpty()) {
            handler.post(onFinished)
            return
        }
        val base = SystemClock.uptimeMillis() - ticks.first().t
        ticks.forEachIndexed { i, tick ->
            val last = i == ticks.lastIndex
            handler.postAtTime({
                tick.signals.forEach(sink::onSignal)
                if (last) onFinished()
            }, token, base + tick.t)
        }
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(token)
    }
}
