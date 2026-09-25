package com.mirror.cast.qr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 扫码界面：**竖屏 + 中间一个清清楚楚的取景框**。
 *
 * 为什么不用库自带的 `CaptureActivity`：它的屏幕方向被库自己的 Manifest 写死成
 * `fullSensor`，扫二维码时横过来很别扭，取景框在横屏下也不显眼。
 *
 * 这里直接用库提供的 [DecoratedBarcodeView] 自己搭 —— 它就是
 * "相机预览 + 取景框 + 状态文字"三件套的组合控件，取景框本来就画在里面，
 * 所以既拿到竖屏，又保证中间有明确的扫描区域。
 */
class ScanActivity : Activity() {

    private lateinit var barcodeView: DecoratedBarcodeView

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, result.text))
            finish()
        }

        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 扫码时别让屏幕自己灭掉
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        barcodeView = DecoratedBarcodeView(this)
        barcodeView.setStatusText("对准接收端的二维码")
        setContentView(barcodeView)
        barcodeView.decodeContinuous(callback)
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    companion object {
        const val EXTRA_RESULT: String = "com.mirror.cast.qr.SCAN_RESULT"
    }
}