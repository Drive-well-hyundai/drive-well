package com.driveproof.source

import android.util.Log
import com.driveproof.model.Brake
import com.driveproof.model.Caution
import com.driveproof.model.Gear
import com.driveproof.model.NaviState
import com.driveproof.model.Odometer
import com.driveproof.model.RoadLimit
import com.driveproof.model.Signal
import com.driveproof.model.Speed
import com.driveproof.model.Steering
import com.driveproof.model.Tbt
import com.driveproof.model.Telltale
import com.driveproof.model.TurnSignal

/**
 * 시연 판정 기록. logcat 태그 `DP_DEMO`, 줄마다 출처를 붙인다(demo-replay.md "구조").
 * [SDK]는 SDK에서 받은 값, [계산]은 앱이 계산한 값, [재생]은 시나리오 파일 값이다.
 * 시연자는 호스트 PC에서 `adb logcat -s DP_DEMO`로 본다.
 */
object DemoLog {
    const val TAG = "DP_DEMO"

    fun sdk(msg: String) { Log.i(TAG, "[SDK] $msg") }
    fun calc(msg: String) { Log.i(TAG, "[계산] $msg") }
    fun replay(msg: String) { Log.i(TAG, "[재생] $msg") }

    /** 출처를 붙이지 않는 줄. 모드 전환처럼 신호가 아닌 기록에만 쓴다 */
    fun info(msg: String) { Log.i(TAG, msg) }
    fun warn(msg: String) { Log.w(TAG, msg) }

    /** 신호 한 개를 사람이 읽는 한 줄로 바꾼다. 시각은 초 단위로 앞에 붙인다 */
    fun describe(s: Signal): String {
        val body = when (s) {
            is Speed -> "속도 ${fmt(s.kmh)}km/h"
            is Gear -> "기어 ${s.gear}"
            is TurnSignal -> "깜빡이 ${s.state}"
            is Brake -> if (s.pressed) "브레이크 밟음" else "브레이크 뗌"
            is Steering -> if (s.available) "조향 ${fmt(s.deg)}도" else "조향 수신 안 됨"
            is com.driveproof.model.Heading -> "진행 방향 상태 변경"
            is Telltale -> "경고등 ${s.name} ${s.state}"
            is Tbt -> "회전 ${s.type} 남은 거리 ${fmt(s.distanceM)}m"
            is Caution -> if (s.type == null) "주의 지점 끝"
                else "주의 지점 ${s.type} 남은 거리 ${s.remainM?.let(::fmt)}m" + (s.limitKmh?.let { " 제한 ${it}km/h" } ?: "")
            is RoadLimit -> if (s.kmh == 0) "제한속도 모름" else "제한속도 ${s.kmh}km/h"
            is NaviState -> if (s.routeDrive) "경로 안내 중" else "경로 안내 없음"
            is Odometer -> "주행거리 ${fmt(s.km)}km"
        }
        return "t=${String.format(java.util.Locale.ROOT, "%.1f", s.t / 1000.0)}s $body"
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
}
