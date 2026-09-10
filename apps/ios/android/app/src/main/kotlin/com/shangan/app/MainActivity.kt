package com.shangan.app

import android.os.BatteryManager
import android.content.Context
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/** 上岸入口；全屏播放器通过系统服务读取电量，不需要额外权限。 */
class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.shangan/app-update")
            .setMethodCallHandler(AppUpdateBridge(this))
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.shangan/device-status")
            .setMethodCallHandler { call, result ->
                if (call.method == "battery") {
                    val manager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    result.success(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                } else result.notImplemented()
            }
    }
}
