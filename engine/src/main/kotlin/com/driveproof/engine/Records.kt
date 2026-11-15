package com.driveproof.engine

import com.driveproof.model.BasicKind
import com.driveproof.model.Exempt
import com.driveproof.model.Principle

/** 확정된 장면이나 이벤트. `:service`가 한 줄씩 저장한다 */
sealed interface Confirmed {
    /** 일어난 시각(ms). 신호의 `t`와 같은 시계다 */
    val at: Long
}

/**
 * 기본 항목 이벤트 하나.
 * [value]는 급가속·급감속이면 구간 안 최대 가감속(km/h/s), 과속이면 구간 안 최고 평활 속도(km/h)다.
 * [kmh]는 이벤트가 시작될 때의 평활 속도다.
 */
data class BasicRec(
    val kind: BasicKind,
    override val at: Long,
    val value: Double,
    val kmh: Double,
    val exempt: Exempt = Exempt.NONE,
) : Confirmed

/** 장면 종류. 한 장 문구의 "좌회전", "우회전", "유턴", "과속방지턱", "스쿨존"이다 */
enum class SceneKind { LEFT_TURN, RIGHT_TURN, U_TURN, SPEED_BUMP, SCHOOL_ZONE }

/** 회전을 찾은 경로. VEHICLE은 핸들과 속도로 추정한 회전이다 */
enum class ScenePath { NAVI, VEHICLE }

/**
 * 장면 하나. 평가 기회 하나다(driving-record.md "장면 판정").
 * [s]는 부분 점수(0~1)다.
 * 미리 켜기 장면에서 [onDistanceM]은 깜빡이를 켠 거리(m)이고, 안 켰거나 반대 방향이면 null이다.
 * [criterionM]은 기준 거리(30m)다.
 * 미리 줄이기 장면에서 [maxDecel]은 회전 지점 50m 전부터의 최대 감속(km/h/s),
 * [hardDecel]은 구간 시작 속도의 급감속 기준(14 또는 15), [passKmh]는 지점 통과 속도다.
 * [windowFrom]부터 [windowTo]까지가 그 구간의 시각이다.
 * 과속방지턱 장면의 [s]는 감속 점수([decelScore])와 통과 속도 점수([passScore]) 중 낮은 값이다.
 * 구역 지키기 장면에서 [maxKmh]는 구역 안 최고 속도, [limitKmh]는 제한속도다.
 */
data class SceneRec(
    val principle: Principle,
    val kind: SceneKind,
    override val at: Long,
    val s: Double,
    val path: ScenePath,
    val onDistanceM: Double? = null,
    val criterionM: Double? = null,
    val maxDecel: Double? = null,
    val hardDecel: Double? = null,
    val passKmh: Double? = null,
    val windowFrom: Long? = null,
    val windowTo: Long? = null,
    val decelScore: Double? = null,
    val passScore: Double? = null,
    val maxKmh: Double? = null,
    val limitKmh: Int? = null,
) : Confirmed {
    /** 화면에 "추정"을 붙인다 */
    val estimated: Boolean get() = path == ScenePath.VEHICLE
}

/**
 * 위험 장면. 경고등이 꺼짐에서 켜짐·깜빡임으로 바뀐 순간이다(driving-record.md "위험 장면"). 점수에 넣지 않는다.
 * [lamp]는 SDK 이름 그대로다(`FCA`, `ABS`, `ESC`, `DRIVER_ATTENTION`). 표시등 상태라 "작동"이라고 쓰지 않는다.
 * [maxDecel]은 앞뒤 5초 안의 최대 감속(km/h/s), [kmhBefore]는 5초 전 속도, [brake]는 그 순간의 브레이크 페달이다.
 * [exempts]가 true면 앞뒤 5초의 급감속을 기본 항목에서 뺀다. `DRIVER_ATTENTION`은 기록만 한다.
 */
data class RiskRec(
    val lamp: String,
    override val at: Long,
    val maxDecel: Double,
    val kmhBefore: Double,
    val brake: Boolean,
    val exempts: Boolean,
) : Confirmed

/**
 * 주행 하나의 결과. 기어 P 때 [TripEngine.end]가 낸다.
 * [hadGap]이면 속도 콜백이 3초 넘게 빈 구간이 있어 그 구간을 판정하지 않았다.
 * [scenes]는 시각순이다. [Consts.minKm]보다 짧은 주행이면 비어 있다.
 * 회전 50m 구간 안의 급감속은 [basic]에 [Exempt.SCENE]으로, 경고등 앞뒤 5초의 급감속은 [Exempt.TELLTALE]로 남는다.
 * 둘 다 기본 항목 횟수에서는 뺀다. 경고등 구간과 겹치는 미리 줄이기 장면은 [scenes]에 없다.
 * [card]는 이 주행의 한 장이다. 점수 줄은 누적 기록이 있어야 해서 `:service`가 붙인다.
 */
data class TripResult(
    val km: Double,
    val scenes: List<SceneRec>,
    val basic: List<BasicRec>,
    val risks: List<RiskRec>,
    val hadGap: Boolean,
    val card: Card,
) {
    /** 기본 항목에서 뺀 급감속(`ExemptEvent`). 경고등 면제와 장면 구간 면제 둘 다다 */
    val exempts: List<BasicRec> get() = basic.filter { it.exempt != Exempt.NONE }
}
