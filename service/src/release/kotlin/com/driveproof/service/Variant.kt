package com.driveproof.service

import android.content.Context
import com.driveproof.source.SignalSink
import com.driveproof.source.SourceKind
import com.driveproof.source.live.LiveSource

internal val buildVariant: BuildVariant = object : BuildVariant {

    private var live: LiveSource? = null

    override fun startSources(context: Context, sink: SignalSink, liveAllowed: Boolean) {
        if (!liveAllowed) return
        live?.stop()
        live = LiveSource(context).also { it.start(sink) }
    }

    override fun stopSources() {
        live?.stop()
        live = null
    }

    override fun sourceKind() = SourceKind.LIVE
}
