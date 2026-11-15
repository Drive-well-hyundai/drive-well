package com.driveproof.source.replay

import com.driveproof.model.Signal

/**
 * 시나리오 JSON을 0.1초 격자의 시각순 신호 목록으로 바꾸는 재생기.
 * 구현은 E-1(`:model`의 시나리오 파서와 10Hz 재생기)이 만든다.
 * 규격은 implementation.md "시나리오 파일 규격"이다.
 */
fun interface ScenarioPlayer {
    fun play(json: String): List<Signal>
}
