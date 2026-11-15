package com.driveproof.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportQrTest {
    @Test fun koreanReportCanBeDecodedAsPlainText() {
        val report = "2026년 10월 · 총점 83점 · 130.4km\n미리 켜기 7% → 50% · 평가 기회 20회\n미리 줄이기 44% → 63%\n스쿨존 제한속도 준수 75%\n만든 날짜 2026-11-12 · 점수 기준 2026-11-10"
        val qr = reportQr(report, 340)
        val pixels = IntArray(qr.width * qr.height) { i -> if (qr[i % qr.width, i / qr.width]) -0x1000000 else -1 }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width, qr.height, pixels)))
        assertEquals(report, QRCodeReader().decode(image).text)
    }
}
