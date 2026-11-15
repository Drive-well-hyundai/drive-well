package com.driveproof.service

import android.content.Context
import com.driveproof.source.SignalSink
import com.driveproof.source.SourceKind

/**
 * 빌드 종류마다 다른 부분. 구현은 `src/release`와 `src/demo`의 `buildVariant`다.
 * - release: LiveSource만 쓴다
 * - demo: DemoController가 live·replay(·hybrid)를 바꿔 끼운다
 */
internal interface BuildVariant {

    /** 신호 출처를 켜고 [sink]로 받는다. 메인 스레드에서 부른다 */
    fun startSources(context: Context, sink: SignalSink, liveAllowed: Boolean)

    fun stopSources()

    /** 지금 신호를 내는 출처. `Trip.source`에 쓴다 */
    fun sourceKind(): SourceKind?
}
