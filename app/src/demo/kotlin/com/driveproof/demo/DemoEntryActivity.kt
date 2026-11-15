package com.driveproof.demo

import android.app.Activity
import android.os.Bundle
import com.driveproof.service.demo.DemoController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 시연 진입. 화면 없이 모드를 DemoController에 넘기고 바로 닫힌다.
 * Activity라서 정차 중에만 시작된다(implementation.md "시연 진입").
 *
 * adb shell am start -a dp.DEMO --es mode replay --es scenario s1
 */
class DemoEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras
        val args = extras?.keySet()
            ?.filter { it != "mode" }
            ?.associateWith { extras.getString(it) }
            .orEmpty()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                DemoController.handle(this@DemoEntryActivity, intent.getStringExtra("mode"), args)
            } finally {
                finish()
            }
        }
    }
}
