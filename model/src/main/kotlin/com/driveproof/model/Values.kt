package com.driveproof.model

/** 기어. 시나리오의 `gear` 값과 이름이 같다 */
enum class GearPos { P, R, N, D }

/** 깜빡이. 시나리오의 `turn_signal` 값과 이름이 같다 */
enum class Blink { OFF, LEFT, RIGHT }

/** 경고등 상태. Pleos `TelltaleState`와 이름이 같다 */
enum class Lamp { ON, BLINK, OFF }

/**
 * 회전 안내 종류. 이름은 NaviHelper `TurnType`과 같다.
 * 판정에 쓰지 않는 종류(나들목, 터널 등 43개 중 나머지)는 [OTHER]로 받는다.
 */
enum class TurnType {
    TURN_LEFT, TURN_RIGHT, TURN_UTURN,
    TURN_SHARP_L, TURN_SHARP_R, TURN_KEEP_L, TURN_KEEP_R,
    TURN_STRAIGHT, OTHER;

    companion object {
        fun fromName(name: String): TurnType = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/**
 * 주의 지점 종류. 이름은 NaviHelper `CautionPointType`과 같다.
 * 판정에 쓰지 않는 종류(23개 중 나머지)는 [OTHER]로 받는다.
 */
enum class CautionType {
    SCHOOL_ZONE, SPEED_BUMP, OTHER;

    companion object {
        fun fromName(name: String): CautionType = entries.firstOrNull { it.name == name } ?: OTHER
    }
}
