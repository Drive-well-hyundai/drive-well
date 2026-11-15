package com.driveproof.service.trip

import com.driveproof.model.Consts
import com.driveproof.model.Gear
import com.driveproof.model.GearPos
import com.driveproof.model.Odometer
import com.driveproof.model.RoadLimit
import com.driveproof.model.Signal
import com.driveproof.model.Speed

/** 서비스 상태(implementation.md "서비스 상태") */
enum class TripState { IDLE, RECORDING, RESUME_WAIT }

/** 주행거리를 어디서 구했나(implementation.md "저장 스키마" `kmSource`) */
enum class KmSource { ODOMETER, INTEGRAL }

sealed interface TripEvent {
    val t: Long

    /** 기어가 P가 아니고 속도 > 0. 새 주행이다 */
    data class Started(override val t: Long) : TripEvent

    /**
     * 기어 P. 주행을 끝낸다(ENDED 또는 SHORT, 한 장, 주차 알림은 이때 한다).
     * 30초 안에 다시 출발하면 [Resumed]로 같은 주행을 다시 연다(implementation.md "주행 끝 순서").
     */
    data class Parked(
        override val t: Long,
        val startAt: Long,
        val km: Double,
        val kmSource: KmSource,
        val short: Boolean,
        val hadGap: Boolean = false,
        val odoStart: Double? = null,
        val limitKnownKm: Double = 0.0,
    ) : TripEvent

    /** 30초 안에 다시 출발했다. 같은 주행을 다시 열고 이어서 기록한다 */
    data class Resumed(override val t: Long) : TripEvent

    /** 이어 붙이기 창(30초)이 닫혔다. 이 주행은 더 열리지 않는다. 값은 마지막 [Parked]와 같다 */
    data class Ended(
        override val t: Long,
        val startAt: Long,
        val km: Double,
        val kmSource: KmSource,
        val short: Boolean,
        val hadGap: Boolean = false,
    ) : TripEvent
}

/**
 * 주행의 시작과 끝을 가른다. 규칙 원본은 driving-record.md "주행의 시작과 끝"이다.
 * 순수 Kotlin이다. 시각은 신호의 `t`에서만 읽는다.
 *
 * 주행거리: Odometer 시작·끝 차이가 0보다 크면 그 값, 아니면 속도를 적분한 값.
 * 비정상 종료 뒤 [restore]로 이어 쓰면 저장해 둔 거리에 다시 산 뒤의 거리를 더한다.
 *
 * @param minKm 최소 주행거리. release 1km, demo 0.2km
 * @param resumeWindowMs 기어 P 뒤 같은 주행으로 잇는 시간(30초, 예시값)
 */
class TripStateMachine(
    private val minKm: Double,
    private val resumeWindowMs: Long = 30_000L,
) {
    var state = TripState.IDLE
        private set

    private var gear: GearPos? = null
    private var speedKmh = 0.0
    private var lastSpeedT: Long? = null
    private var speedAt: Long? = null
    private var speedLimitKnown = false
    private var knownKm = 0.0
    private val speedGapMs = Consts().speedGapMs

    private var startAt = 0L
    private var parkedAt = 0L
    private var integralKm = 0.0
    var odoStart: Double? = null
        private set
    private var odoLast: Double? = null
    private var kmBase = 0.0
    private var hadGap = false

    /**
     * 서비스가 비정상 종료 뒤 다시 떴고 OPEN인 주행이 있다(implementation.md "오류 처리").
     * 기록 중으로 되돌린다. 기어가 P가 아니면 이어서 기록하고, 첫 기어 신호가 P면 그 주행을 끝낸다.
     * 끊긴 구간은 거리에 넣지 않는다. 끝날 때 [TripEvent]의 hadGap이 켜진다.
     */
    fun restore(startAt: Long, kmSoFar: Double, knownKmSoFar: Double = 0.0) {
        state = TripState.RECORDING
        this.startAt = startAt
        kmBase = kmSoFar
        integralKm = 0.0
        odoStart = null
        hadGap = true
        gear = null
        speedKmh = 0.0
        lastSpeedT = null
        speedAt = null
        speedLimitKnown = false
        knownKm = knownKmSoFar
    }

    /** 지금 주행의 거리(km). 기록 중이 아니면 0 */
    val km: Double get() = if (state == TripState.IDLE) 0.0 else currentKm().first
    val limitKnownKm: Double get() = knownKm.coerceAtMost(km)

    fun onSignal(s: Signal): List<TripEvent> {
        val out = ArrayList<TripEvent>(1)
        // 이어 붙이기 대기가 신호 시각으로 30초를 넘었으면 먼저 끝낸다
        if (state == TripState.RESUME_WAIT && s.t - parkedAt >= resumeWindowMs) out += end(parkedAt + resumeWindowMs)

        when (s) {
            is Speed -> {
                integrate(s.t)
                speedKmh = s.kmh
                speedAt = s.t
            }
            is Gear -> {
                integrate(s.t)
                gear = s.gear
            }
            is Odometer -> {
                if (state != TripState.IDLE) {
                    if (odoStart == null) odoStart = s.km
                    odoLast = s.km
                } else {
                    odoLast = s.km
                }
            }
            is RoadLimit -> {
                integrate(s.t)
                speedLimitKnown = s.kmh > 0
                return out
            }
            else -> return out
        }

        val moving = gear != null && gear != GearPos.P && speedKmh > 0.0
        when (state) {
            TripState.IDLE -> if (moving) out += start(s.t)
            TripState.RECORDING -> if (gear == GearPos.P) {
                state = TripState.RESUME_WAIT
                parkedAt = s.t
                val (km, src) = currentKm()
                out += TripEvent.Parked(s.t, startAt, km, src, short = km < minKm, hadGap = hadGap,
                    odoStart = odoStart, limitKnownKm = limitKnownKm)
            }
            TripState.RESUME_WAIT -> if (moving) {
                state = TripState.RECORDING
                out += TripEvent.Resumed(s.t)
            }
        }
        return out
    }

    /**
     * 신호가 더 오지 않을 때 쓴다(재생이 기어 P로 끝난 뒤 등). 서비스가 실제 시간 30초 뒤 부른다.
     * 이어 붙이기 대기면 주행을 끝낸다.
     */
    fun expire(): List<TripEvent> =
        if (state == TripState.RESUME_WAIT) listOf(end(parkedAt + resumeWindowMs)) else emptyList()

    private fun start(t: Long): TripEvent {
        state = TripState.RECORDING
        startAt = t
        integralKm = 0.0
        knownKm = 0.0
        kmBase = 0.0
        hadGap = false
        odoStart = odoLast
        return TripEvent.Started(t)
    }

    private fun end(t: Long): TripEvent {
        val (km, src) = currentKm()
        state = TripState.IDLE
        return TripEvent.Ended(t, startAt, km, src, short = km < minKm, hadGap = hadGap)
    }

    /** 직전 속도를 지금까지 유지한 것으로 보고 더한다(직전 값 유지) */
    private fun integrate(t: Long) {
        val last = lastSpeedT
        lastSpeedT = t
        if (last == null || state == TripState.IDLE || t <= last) return
        integralKm += speedKmh * (t - last) / 3_600_000.0
        val speedSample = speedAt
        if (state == TripState.RECORDING && speedLimitKnown && speedSample != null && t - speedSample <= speedGapMs) {
            knownKm += speedKmh * (t - last) / 3_600_000.0
        }
    }

    private fun currentKm(): Pair<Double, KmSource> {
        val a = odoStart
        val b = odoLast
        return if (a != null && b != null && b - a > 0.0) (kmBase + b - a) to KmSource.ODOMETER
        else (kmBase + integralKm) to KmSource.INTEGRAL
    }
}
