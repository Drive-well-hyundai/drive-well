package com.driveproof.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 부팅이 끝나면 TripService를 띄운다. `BOOT_COMPLETED` 수신은 백그라운드 포그라운드 서비스 시작 제한의 예외다.
 * 설치 뒤 앱을 한 번 열어야 이 방송을 받는다(driving-record.md "백그라운드 실행").
 * 동의 전에는 띄우지 않는다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                TripService.ensureRunning(context.applicationContext)
            } finally {
                result.finish()
            }
        }
    }
}
