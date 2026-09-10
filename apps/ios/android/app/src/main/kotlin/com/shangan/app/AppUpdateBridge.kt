package com.shangan.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.security.MessageDigest

/** 更新平台边界：读取真实版本、私有缓存路径、权限引导和系统确认安装。 */
class AppUpdateBridge(private val activity: FlutterActivity) : MethodChannel.MethodCallHandler {
    private val directory get() = File(activity.cacheDir, "app-updates").apply { mkdirs() }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "info" -> {
                    @Suppress("DEPRECATION")
                    val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    result.success(mapOf("version" to info.versionName, "directory" to directory.path, "simulator" to false))
                }
                "canInstall" -> result.success(Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls())
                "requestInstallPermission" -> {
                    if (Build.VERSION.SDK_INT >= 26) activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    result.success(null)
                }
                "install" -> install(call, result)
                else -> result.notImplemented()
            }
        } catch (_: Exception) {
            result.error("APP_INSTALL_FAILED", "无法打开系统安装器，请检查安装权限后重试", null)
        }
    }

    /** 文件必须在私有更新目录，且包名、版本、签名与摘要符合预期；不申请存储权限。 */
    private fun install(call: MethodCall, result: MethodChannel.Result) {
        val path = call.argument<String>("path") ?: throw IllegalArgumentException()
        val expected = call.argument<String>("sha256") ?: throw IllegalArgumentException()
        val version = call.argument<String>("version") ?: throw IllegalArgumentException()
        val file = File(path).canonicalFile
        require(file.parentFile == directory.canonicalFile && file.name == "update-$expected.apk")
        // 摘要计算在工作线程，避免大 APK 阻塞 UI。
        Thread {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == expected)
                @Suppress("DEPRECATION")
                val incoming = activity.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) ?: throw IllegalArgumentException()
                @Suppress("DEPRECATION")
                val installed = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val signatureMatches = !incoming.signatures.isNullOrEmpty() && !installed.signatures.isNullOrEmpty() && incoming.signatures?.map { it.toCharsString() }?.toSet() == installed.signatures?.map { it.toCharsString() }?.toSet()
                require(incoming.packageName == activity.packageName && incoming.versionName == version && signatureMatches)
                activity.runOnUiThread {
                    try {
                        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", file)
                        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        result.success(null)
                    } catch (_: Exception) { result.error("APP_INSTALL_FAILED", "系统未能打开安装包，请重试", null) }
                }
            } catch (_: Exception) {
                activity.runOnUiThread { result.error("APP_PACKAGE_INVALID", "安装包校验失败或与当前 App 签名不一致，无法覆盖安装", null) }
            }
        }.start()
    }
}
