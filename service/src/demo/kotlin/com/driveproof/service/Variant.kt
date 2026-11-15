package com.driveproof.service

import android.content.Context
import com.driveproof.service.demo.DemoController
import com.driveproof.source.SignalSink
import com.driveproof.source.SourceKind

internal val buildVariant: BuildVariant = object : BuildVariant {

    override fun startSources(context: Context, sink: SignalSink, liveAllowed: Boolean) = DemoController.attach(context, sink, liveAllowed)

    override fun stopSources() = DemoController.detach()

    override fun sourceKind(): SourceKind? = DemoController.currentKind
}
