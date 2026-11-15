package com.driveproof.source.live

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.driveproof.model.Heading
import com.driveproof.source.DemoLog
import com.driveproof.source.SignalSink
import com.driveproof.source.SignalThread

/** 메인 스레드에서 구독을 관리하고, 좌표를 복사하지 않고 진행 방향만 전달한다. */
class FusedHeadingSource(private val context: Context) {
    private var sdk: FusedLocation? = null
    @Volatile private var listener: FusedLocationListener? = null

    fun update(enabled: Boolean, sink: SignalSink) {
        if (!enabled || !hasPermissions(context)) return stop(sink)
        if (sdk != null || !isAvailable(context)) return
        val location = FusedLocation(context)
        val callback = object : FusedLocationListener {
            private var lastTimestamp: Long? = null // dp-signal에서만 쓴다

            override fun onChanged(data: LocationData?) {
                val at = SystemClock.elapsedRealtime()
                val timestamp = data?.timestampMillis
                val deg = data?.heading?.toDouble()
                SignalThread.run {
                    if (listener !== this) return@run
                    if (!hasPermissions(context)) {
                        sink.onSignal(Heading(at, null))
                        return@run
                    }
                    if (timestamp != null && lastTimestamp?.let { timestamp <= it } == true) return@run
                    lastTimestamp = timestamp
                    sink.onSignal(Heading(at, deg))
                }
            }

            override fun onError(e: Exception) {
                DemoLog.warn("FusedLocation 콜백 오류")
                onChanged(null)
            }
        }
        sdk = location
        listener = callback
        try {
            location.initialize()
            location.registerFusedLocationCallback(callback)
        } catch (_: RuntimeException) {
            DemoLog.warn("FusedLocation 구독 실패")
            stop(sink)
        }
    }

    fun stop(sink: SignalSink) {
        val location = sdk ?: return
        val callback = listener
        listener = null
        sdk = null
        if (callback != null) runCatching { location.unregisterFusedLocationCallback(callback) }
        runCatching { location.release() }
        SignalThread.run { sink.onSignal(Heading(SystemClock.elapsedRealtime(), null)) }
    }

    companion object {
        const val PLEOS_PERMISSION = "pleos.car.permission.FUSED_LOCATION"

        fun isAvailable(context: Context): Boolean = runCatching {
            FusedLocation(context.applicationContext).isSupported()
        }.getOrDefault(false)

        fun hasPermissions(context: Context): Boolean =
            context.checkSelfPermission(PLEOS_PERMISSION) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < 29 ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
}
