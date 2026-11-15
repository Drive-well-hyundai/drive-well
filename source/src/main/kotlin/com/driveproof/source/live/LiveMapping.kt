package com.driveproof.source.live

import com.driveproof.model.Blink
import com.driveproof.model.CautionType
import com.driveproof.model.GearPos
import com.driveproof.model.Lamp

/**
 * SDK 값을 `:model` 값으로 바꾸는 순수 함수. JVM 단위 테스트로 검증한다.
 * SDK 클래스 대신 이름·정수를 받아 Android 없이 돈다.
 */
internal object LiveMapping {

    /** AAOS `PERF_VEHICLE_SPEED`는 m/s다 */
    fun kmh(mps: Float): Double = mps * 3.6

    // AAOS VehicleGear 값 (android.car.VehicleGear)
    private const val GEAR_NEUTRAL = 0x0001
    private const val GEAR_REVERSE = 0x0002
    private const val GEAR_PARK = 0x0004
    private const val GEAR_DRIVE = 0x0008

    /**
     * AAOS `GEAR_SELECTION` 값. 에뮬레이터에서 D는 8, P는 4였다(sdk.md 11/10).
     * 수동 변속단(1단 이상)은 D로 본다. 모르는 값은 null.
     */
    fun gear(value: Int): GearPos? = when {
        value == GEAR_PARK -> GearPos.P
        value == GEAR_REVERSE -> GearPos.R
        value == GEAR_NEUTRAL -> GearPos.N
        value == GEAR_DRIVE -> GearPos.D
        value > GEAR_DRIVE -> GearPos.D
        else -> null
    }

    /** Pleos `TurnSignalValue` 이름(OFF, LEFT, RIGHT) */
    fun blink(name: String?): Blink? = when (name) {
        "OFF" -> Blink.OFF
        "LEFT" -> Blink.LEFT
        "RIGHT" -> Blink.RIGHT
        else -> null
    }

    /**
     * 조향 부호 보정. 계약은 "앱이 받는 값 기준, 왼쪽이 음수"다(implementation.md "시나리오 파일 규격").
     * 에뮬레이터 SDK 값을 그대로 기준으로 삼아 1이다. Virtual Console에 넣는 값은 이와 부호가 반대다(sdk.md 11/10).
     * 실차 부호는 확인 불가다. 실차에서 반대로 나오면 이 값만 -1로 바꾼다.
     */
    const val STEERING_SIGN = 1.0

    fun steeringDeg(raw: Float): Double = raw * STEERING_SIGN

    /** NaviHelper `NavigationStatus` 이름 */
    fun routeDrive(name: String?): Boolean = name == "ROUTE_DRIVE"

    /** Pleos `TelltaleState` 이름(ON, BLINK, OFF) */
    fun lamp(name: String?): Lamp? = when (name) {
        "ON" -> Lamp.ON
        "BLINK" -> Lamp.BLINK
        "OFF" -> Lamp.OFF
        else -> null
    }

    /**
     * `registerTelltales`는 지금 켜진 경고등 목록 전체를 준다. 끄면 목록에서 빠진다(sdk.md 11/10: 끄면 빈 목록).
     * 직전 상태와 비교해 바뀐 것만 돌려준다. 목록에서 빠진 것은 OFF다.
     * @param prev 직전에 켜져 있던 경고등(이름 → 상태). OFF는 넣지 않는다
     * @param now 이번 목록(이름, 상태 이름)
     * @return 새 상태와 바뀐 경고등
     */
    fun telltaleChanges(prev: Map<String, Lamp>, now: List<Pair<String, String?>>): Pair<Map<String, Lamp>, List<Pair<String, Lamp>>> {
        val next = LinkedHashMap<String, Lamp>()
        for ((name, state) in now) {
            val lamp = lamp(state) ?: continue
            if (lamp != Lamp.OFF) next[name] = lamp
        }
        val changes = ArrayList<Pair<String, Lamp>>()
        for ((name, lamp) in next) if (prev[name] != lamp) changes += name to lamp
        for (name in prev.keys) if (name !in next) changes += name to Lamp.OFF
        return next to changes
    }

    /**
     * `onCautionPointInfo` 목록에서 판정에 쓰는 가장 가까운 지점(스쿨존, 과속방지턱).
     * @param points (CautionPointType 이름, 남은 거리 m)
     * @return 없으면 null(주의 지점이 사라졌다)
     */
    fun nearestCaution(points: List<Pair<String, Int>>): Pair<CautionType, Int>? =
        points.asSequence()
            .map { (name, remain) -> CautionType.fromName(name) to remain }
            .filter { (type, remain) -> type != CautionType.OTHER && remain >= 0 }
            .minByOrNull { it.second }

    /**
     * 스쿨존 제한속도. `CameraAlert` 중 `SPEED_CAMERA_SCHOOL_ZONE`(신호 단속 겸용 포함)의 `speedLimit`(driving-record.md).
     * 여럿이면 가장 가까운 것. 없거나 0이면 null(엔진은 30km/h로 본다).
     * @param cameras (CameraType 이름, speedLimit, 남은 거리 m)
     */
    fun schoolZoneLimit(cameras: List<Triple<String, Int, Int>>): Int? =
        cameras.asSequence()
            .filter { it.first.startsWith("SPEED_CAMERA_SCHOOL_ZONE") && it.second > 0 }
            .minByOrNull { it.third }
            ?.second
}
