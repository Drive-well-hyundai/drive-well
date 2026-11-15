package com.driveproof.service.trip

import com.driveproof.model.BasicKind
import com.driveproof.model.Principle
import com.driveproof.engine.SceneKind
import com.driveproof.engine.TripEngine
import com.driveproof.model.Consts
import com.driveproof.model.Signal
import com.driveproof.service.BuildConfig
import com.driveproof.source.DemoLog
import com.driveproof.source.SignalSink
import java.util.Locale

/**
 * `TripService.engine`에 붙는 판정 엔진. 프로세스에 하나다. dp-signal 스레드에서만 부른다.
 * 엔진은 주행이 아닐 때도 신호를 받는다. 주행은 엔진이 [TripEngine.end] 뒤 다시 움직일 때 스스로 가른다.
 * 기본 항목 이벤트를 세거나 회전을 통과하면 시연 판정 기록(`DP_DEMO`)에 [계산] 줄을 남긴다.
 */
internal object EngineHost : SignalSink {
    var engine = create(true)
        private set

    fun reset(controlsEnabled: Boolean = true) { engine = create(controlsEnabled) }

    private fun create(controlsEnabled: Boolean) = TripEngine(Consts(minKm = BuildConfig.MIN_KM), controlsEnabled).also { engine ->
        engine.onBasic = { r ->
            val line = when (r.kind) {
                BasicKind.DECEL -> "급감속 후보 · ${one(r.value)} km/h/s"
                BasicKind.ACCEL -> "급가속 후보 · ${one(r.value)} km/h/s"
                BasicKind.OVER -> "과속 후보 · ${one(r.value)} km/h"
            }
            DemoLog.calc(line)
        }
        engine.onRisk = { r ->
            // 경고등은 표시등 상태다. "작동"이라고 쓰지 않는다 (driving-record.md "위험 장면")
            val note = if (r.exempts) " · 앞뒤 5초 급감속은 기본 항목에서 뺀다" else ""
            DemoLog.calc("${r.lamp} 경고등 켜짐$note")
        }
        engine.onScene = { s ->
            val kind = when (s.kind) {
                SceneKind.LEFT_TURN -> "좌회전"
                SceneKind.RIGHT_TURN -> "우회전"
                SceneKind.U_TURN -> "유턴"
                SceneKind.SPEED_BUMP -> "과속방지턱"
                SceneKind.SCHOOL_ZONE -> "스쿨존"
            }
            val on = s.onDistanceM
            val what = when {
                s.principle == Principle.ZONE -> "최고 ${one(s.maxKmh ?: 0.0)} km/h (제한 ${s.limitKmh})"
                s.kind == SceneKind.SPEED_BUMP -> "${one(s.passKmh ?: 0.0)} km/h · 최대 감속 ${one(s.maxDecel ?: 0.0)} km/h/s"
                s.principle == Principle.DECEL -> "최대 감속 ${one(s.maxDecel ?: 0.0)} km/h/s"
                // 기준(30m) 이상이면 통과, 그보다 가까우면 늦음, 안 켰으면 미점등 (demo-replay.md "H 대본")
                on == null -> "깜빡이 없음 · 미점등"
                s.s >= 1.0 -> "깜빡이 ${one(on)}m 전 · 통과"
                else -> "깜빡이 ${one(on)}m 전 · 늦음"
            }
            DemoLog.calc("$kind 통과 · $what → ${String.format(Locale.ROOT, "%.2f", s.s)}")
        }
    }

    override fun onSignal(s: Signal) = engine.onSignal(s)

    private fun one(x: Double) = String.format(Locale.ROOT, "%.1f", x)
}
