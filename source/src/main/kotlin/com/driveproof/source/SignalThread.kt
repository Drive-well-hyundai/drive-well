package com.driveproof.source

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * SDK 콜백, 재생, 엔진이 모두 도는 스레드 하나("dp-signal").
 * 메인 스레드에서 속도 콜백을 받으면 ANR이 났다(driving-record.md "신호 처리").
 * 스레드가 하나라 엔진에 잠금이 없다.
 */
object SignalThread {
    private val thread: HandlerThread by lazy {
        HandlerThread("dp-signal").apply { start() }
    }

    val looper: Looper get() = thread.looper
    val handler: Handler by lazy { Handler(looper) }

    fun isCurrent(): Boolean = Looper.myLooper() == looper

    /** dp-signal 스레드에서 [block]을 돌린다. 이미 그 스레드면 바로 돌린다. */
    fun run(block: () -> Unit) {
        if (isCurrent()) block() else handler.post(block)
    }
}
