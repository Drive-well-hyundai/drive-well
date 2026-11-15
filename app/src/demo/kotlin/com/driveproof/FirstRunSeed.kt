package com.driveproof

import android.content.Context
import com.driveproof.service.demo.DemoController
import com.driveproof.source.DemoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 제출 APK(submit)의 첫 실행 때 6개월 시연 기록을 한 번 넣는다(결정 36).
 * 심사위원이 adb 없이 앱을 열어도 한 장, 증명, 월간 리포트를 볼 수 있게 한다. 기록은 "· 시연"으로 보인다.
 * demo 빌드(AUTO_SEED=false)에서는 아무 일도 하지 않는다. 시연자는 `dp.DEMO --es mode seed`를 쓴다.
 */
internal suspend fun firstRunSeed(context: Context) {
    if (!BuildConfig.AUTO_SEED) return
    val prefs = context.getSharedPreferences("dp_first_run", Context.MODE_PRIVATE)
    if (prefs.getBoolean("seeded", false)) return
    val count = withContext(Dispatchers.IO) { DemoController.importFirstRunSeed(context) }
    prefs.edit().putBoolean("seeded", true).apply()
    DemoLog.info("첫 실행 시연 기록 ${count}건을 넣었다")
}
