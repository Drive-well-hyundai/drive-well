package com.driveproof.source

import com.driveproof.model.Signal

/** 신호를 받는 쪽. 항상 dp-signal 스레드([SignalThread])에서 부른다. */
fun interface SignalSink {
    fun onSignal(s: Signal)
}

enum class SourceKind { LIVE, HYBRID, REPLAY }

/**
 * 신호 출처. 계약 원본은 implementation.md "모듈 간 계약"이다.
 * 엔진은 신호가 실차에서 왔는지 재생에서 왔는지 모른다.
 */
interface VehicleDataSource {
    val kind: SourceKind
    fun start(sink: SignalSink)
    fun stop()
}
