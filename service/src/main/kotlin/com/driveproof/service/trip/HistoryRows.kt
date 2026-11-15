package com.driveproof.service.trip

import com.driveproof.engine.BasicRec
import com.driveproof.engine.Card
import com.driveproof.engine.LineKind
import com.driveproof.engine.SceneRec
import com.driveproof.engine.SeedRecord
import com.driveproof.model.Principle
import com.driveproof.scoring.BasicRow
import com.driveproof.scoring.History
import com.driveproof.scoring.SceneRow
import com.driveproof.scoring.TripRow

/**
 * 주행 하나를 점수 입력으로 바꾼다. `:engine`의 결과와 `:scoring`의 입력을 잇는 곳이다.
 *
 * - [endAt]은 달력 시각(ms)이다. 장면과 이벤트도 이 시각으로 넣는다. 신호의 `t`는 부팅 뒤 시각이라 쓰지 않는다.
 * - 장면의 행동 값: 미리 켜기는 켠 거리, 미리 줄이기는 최대 감속, 구역 지키기는 구역 안 최고 속도다.
 * - 고칠 점은 한 장의 FIX 줄이 말하는 원칙이다. 주간 요약과 월 집계가 센다.
 */
internal fun historyOf(endAt: Long, km: Double, scenes: List<SceneRec>, basic: List<BasicRec>, card: Card): History = History(
    trips = listOf(TripRow(endAt, km, card.lines.firstOrNull { it.kind == LineKind.FIX }?.topic)),
    scenes = scenes.map { s ->
        val value = when (s.principle) {
            Principle.SIGNAL -> s.onDistanceM
            Principle.DECEL -> s.maxDecel
            Principle.ZONE -> s.maxKmh
            Principle.RISK -> null
        }
        SceneRow(endAt, s.principle, s.s, value)
    },
    basics = basic.map { BasicRow(endAt, it.kind, it.exempt) },
)

/** 시드 기록 전체를 점수 입력으로 바꾼다 */
internal fun historyOf(records: List<SeedRecord>): History =
    records.fold(History()) { h, r -> h + historyOf(r.endAt, r.km, r.scenes, r.basic, r.card) }
