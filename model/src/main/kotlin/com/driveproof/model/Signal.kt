package com.driveproof.model

/**
 * 엔진에 들어가는 신호. 계약 원본은 implementation.md "모듈 간 계약"이다.
 *
 * 모든 신호에 수신 시각 [t](ms)가 있다. Live·Hybrid는 `SystemClock.elapsedRealtime()`,
 * Replay는 시나리오 시작부터의 시각이다. 엔진은 시각을 이 값에서만 읽는다.
 */
sealed interface Signal {
    val t: Long
}

/** AAOS `PERF_VEHICLE_SPEED`(m/s) × 3.6 */
data class Speed(override val t: Long, val kmh: Double) : Signal

/** AAOS `GEAR_SELECTION` */
data class Gear(override val t: Long, val gear: GearPos) : Signal

/** Pleos Vehicle TurnSignal */
data class TurnSignal(override val t: Long, val state: Blink) : Signal

/** Pleos Vehicle Brake pedal */
data class Brake(override val t: Long, val pressed: Boolean) : Signal

/** Pleos Vehicle SteeringWheel. 부호 보정 뒤 값이다. 왼쪽이 음수다 */
data class Steering(override val t: Long, val deg: Double, val available: Boolean = true) : Signal

/** FusedLocation의 진행 방향. 수신할 수 없으면 [deg]는 null이다. */
data class Heading(override val t: Long, val deg: Double?) : Signal

/** Pleos Vehicle Safety `registerTelltales`. [name]은 `ABS`, `ESC`, `FCA`, `DRIVER_ATTENTION` 등 SDK 이름 그대로다 */
data class Telltale(override val t: Long, val name: String, val state: Lamp) : Signal

/** NaviHelper `TBTInfo` */
data class Tbt(override val t: Long, val type: TurnType, val distanceM: Double) : Signal

/**
 * NaviHelper `CautionPoint`. [type]이 null이면 주의 지점이 사라진 것이다.
 * [limitKmh]는 스쿨존 제한속도다. `CameraAlert`의 `SPEED_CAMERA_SCHOOL_ZONE` 경고에 `speedLimit`이 있으면 그 값,
 * 없으면 null이다(엔진은 30km/h로 본다, driving-record.md). 시나리오의 `caution.limit`과 같다.
 */
data class Caution(
    override val t: Long,
    val type: CautionType?,
    val remainM: Double?,
    val limitKmh: Int? = null,
) : Signal

/** NaviHelper `onCurrentRoadSpeedLimit`. 0이면 모른다 */
data class RoadLimit(override val t: Long, val kmh: Int) : Signal

/** NaviHelper `NavigationStatus`. `ROUTE_DRIVE`면 true */
data class NaviState(override val t: Long, val routeDrive: Boolean) : Signal

/** Pleos Vehicle Odometer */
data class Odometer(override val t: Long, val km: Double) : Signal
