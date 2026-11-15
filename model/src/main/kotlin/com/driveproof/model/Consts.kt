package com.driveproof.model

/**
 * 판정과 점수의 상수. 값의 원본은 PRD이고 demo-sim.js의 `C`와 같다(implementation.md "기준 구현과 골든 테스트").
 * 모두 예시값이다. 고칠 때는 PRD, demo-sim.js, 이 파일을 함께 고친다.
 * `:engine`과 `:scoring`이 같이 쓰므로 `:model`에 둔다.
 */
data class Consts(
    // 신호 처리 (driving-record.md "신호 처리")
    /** 판정 격자(ms). `DT` */
    val tickMs: Long = 100,
    /** 이벤트로 인정하는 연속 샘플 수. `SUSTAIN` */
    val sustainTicks: Int = 4,
    /** 인정 뒤 같은 종류를 세지 않는 시간(ms). `COOL` */
    val coolMs: Long = 5_000,
    /** 이보다 큰 가감속(km/h/s)은 신호 오류로 버린다. `NOISE` */
    val noiseKmhPerS: Double = 45.0,
    /** 속도 콜백이 이보다 오래 비면 그 구간을 판정하지 않는다(ms) */
    val speedGapMs: Long = 3_000,

    // 기본 항목 (driving-record.md "기본 항목")
    /** 급가속·급감속을 세는 최저 속도(km/h) */
    val basicMinKmh: Double = 6.0,
    /** 과속으로 보는 제한속도 초과분(km/h) */
    val overMarginKmh: Double = 20.0,

    // 주행의 시작과 끝 (driving-record.md "주행의 시작과 끝")
    /** 기어 P 뒤 같은 주행으로 잇는 시간(ms) */
    val resumeWindowMs: Long = 30_000,
    /** 이보다 짧은 주행은 장면을 판정하지 않는다(km). release 1, demo 0.2. `MIN_KM` */
    val minKm: Double = 1.0,

    // 회전 통과, 내비 경로 (driving-record.md "회전 통과")
    /** 남은 거리가 최솟값보다 이만큼 넘게 커지면 통과다(m) */
    val passSlackM: Double = 5.0,
    /** 최솟값이 이보다 크면 통과 시점을 믿을 수 없어 장면을 버린다(m). `NAVI_MIN` */
    val naviMinM: Double = 50.0,

    // 미리 켜기 (driving-record.md "미리 켜기")
    /** 기준 거리(m). 도로교통법 시행령 별표 2. `SIG_REF` */
    val signalRefM: Double = 30.0,
    /** 이보다 먼저 켠 것은 이 거리로 본다(m). `SIG_CAP` */
    val signalCapM: Double = 200.0,

    // 미리 줄이기 (driving-record.md "미리 줄이기", scoring.md "1")
    /** 회전 지점 앞에서 감속을 보는 구간(m). `WIN` */
    val decelWindowM: Double = 50.0,
    /** 최대 감속이 이 이하면 1점이다(km/h/s). `GENTLE` */
    val gentleDecel: Double = 5.0,
    /** 구간 시작보다 이만큼 앞선 급감속도 장면에만 반영한다(ms). 평활 지연을 감안한 여유다 */
    val sceneLeadMs: Long = 500,

    // 주의 지점 (driving-record.md "미리 줄이기", "구역 지키기", scoring.md "1")
    /** 남은 거리가 이 이하가 되면 과속방지턱 통과, 스쿨존 진입이다(m) */
    val cautionPassM: Double = 0.5,
    /** 과속방지턱 통과 속도. 이 이하면 1점, [bumpBadKmh] 이상이면 0점. `BUMP_OK`, `BUMP_BAD` */
    val bumpOkKmh: Double = 30.0,
    val bumpBadKmh: Double = 50.0,
    /** 스쿨존은 진입 뒤 이 거리까지로 본다(m). `ZONE_LEN` */
    val zoneLengthM: Double = 300.0,
    /** 제한속도를 이만큼 넘으면 0점이다(km/h). `ZONE_SPAN` */
    val zoneSpanKmh: Double = 20.0,
    /** 스쿨존 제한속도를 못 받았을 때 쓰는 값(km/h) */
    val zoneDefaultLimitKmh: Int = 30,

    // 위험 장면 (driving-record.md "위험 장면")
    /** 경고등이 켜지기 전과 뒤로 이 시간 안의 급감속은 기본 항목에서 뺀다(ms). `EXEMPT` */
    val exemptMs: Long = 5_000,
    /** 신호를 받기 시작하고 이 시간 안에 처음 본 경고등 상태는 원래 상태로 본다(ms). 켜져 있어도 면제에 쓰지 않는다 */
    val lampBaselineMs: Long = 5_000,

    // 요각 추정, 차량 신호 경로 (driving-record.md "요각 추정")
    /** 조향비. `R` */
    val steerRatio: Double = 14.0,
    /** 축거(m). 아반떼. `L` */
    val wheelbaseM: Double = 2.72,
    /** 핸들 각도가 이보다 작으면 직진으로 본다(도). `DEAD` */
    val steerDeadDeg: Double = 10.0,
    /** 진행 방향 변화가 이보다 느리면 직진으로 본다(도/초). */
    val headingDeadDegPerS: Double = 2.0,
    /** 진행 방향 변화가 이보다 빠르면 그 표본과 진행 중인 판정을 버린다(도/초). */
    val headingMaxDegPerS: Double = 180.0,
    /** 회전으로 보는 속도 범위(km/h). `VMIN`, `VMAX` */
    val turnMinKmh: Double = 3.0,
    val turnMaxKmh: Double = 40.0,
    /** 회전과 유턴으로 보는 누적 요각(도). `TURN_DEG`, `UTURN_DEG` */
    val turnDeg: Double = 60.0,
    val uturnDeg: Double = 160.0,
    /** 회전 시작으로 보는 누적 요각(도) */
    val turnStartDeg: Double = 5.0,
    /** 회전 시작부터 60도까지 걸려도 되는 시간(ms). `TURN_SEC` */
    val turnWithinMs: Long = 10_000,
    /** 평균 요각 속도가 이보다 느리면 완만한 커브로 보고 버린다(도/초). `MIN_RATE` */
    val turnMinRateDegPerS: Double = 15.0,
    /** 주행 시작과 끝에서 이 거리 안의 추정 회전은 버린다(m). `EDGE` */
    val edgeM: Double = 200.0,
    /** 추정 회전 사이가 이보다 가까우면 앞 회전만 남긴다(m). `GAP` */
    val turnGapM: Double = 50.0,
    /** 두 경로의 회전 시각이 이 안이면 같은 회전이다(ms). `DEDUPE` */
    val dedupeMs: Long = 5_000,

    // 점수 (scoring.md)
    /** 원칙 점수의 기준 평균. 기록이 없을 때의 출발점. `MU` */
    val priorMean: Double = 0.7,
    /** 가상의 기회 수. 기회가 이만큼이면 내 기록과 평균을 반반 섞는다. `M` */
    val priorChances: Double = 10.0,
    /** 기준 기본 점수. `B0` */
    val basicPrior: Double = 90.0,
    /** 가상의 주행거리(km). `K` */
    val basicPriorKm: Double = 100.0,
    /** 100km당 1회의 감점. 급감속, 급가속, 과속 */
    val decelPenalty: Double = 5.0,
    val accelPenalty: Double = 4.0,
    val overPenalty: Double = 3.0,
    /** 점수에 넣는 기간(일) */
    val periodDays: Int = 183,
    /** 기간 안 주행거리가 이보다 짧으면 "산정 중"이다(km) */
    val periodMinKm: Double = 500.0,
    /** 성공 장면이 없을 때 부분 점수 평균이 이 이상이면 "해낸 것"으로 쓴다. `PARTIAL` */
    val partialPraise: Double = 0.5,
) {
    /** 급감속 기준(km/h/s). 30km/h 이하 14, 초과 15 */
    fun decelThreshold(kmh: Double): Double = if (kmh <= 30.0) 14.0 else 15.0

    /** 급가속 기준(km/h/s). 10km/h 이하 12, 20km/h 이하 10, 초과 8 */
    fun accelThreshold(kmh: Double): Double = if (kmh <= 10.0) 12.0 else if (kmh <= 20.0) 10.0 else 8.0
}
