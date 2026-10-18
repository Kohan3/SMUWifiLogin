package com.smu.wifilogin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 注销 / 断开 工具（最佳努力实现）。
 *
 * 说明：校园网 Portal 的注销地址与登录页同源，且随设备/会话变化，不能硬编码。
 * 策略：
 *  1. 若用户在「高级设置」中填了 logoutUrl，则优先使用。
 *  2. 否则按触发 URL 派生（常见形态：触发页 + ?action=logout 或同源 /logout）。
 * 由于各校 Portal 厂商（华为 / 锐捷 / 城市热点等）注销接口不同，无法 100% 通用；
 * 若自动注销失败，请在 WebView 打开认证页手动点“注销”。
 */
object LogoutHelper {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun logout(
        config: AppConfig,
        log: (String) -> Unit,
        onResult: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val url = if (config.logoutUrl.isNotBlank()) config.logoutUrl else deriveLogoutUrl(config.triggerUrl)
            log("注销请求地址: $url")
            val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.use { it.body?.string().orEmpty() }
            log("注销响应码: ${resp.code}")
            log("注销响应片段: ${body.take(300)}")

            val ok = resp.isSuccessful
                    || body.lowercase().contains("logout")
                    || body.lowercase().contains("下线")
                    || body.lowercase().contains("success")
                    || body.lowercase().contains("注销成功")
            withContext(Dispatchers.Main) {
                if (ok) {
                    onResult(true, "注销请求已发送（如仍显示在线，请到认证页手动注销）")
                } else {
                    onResult(false, "注销失败，请到 ${config.triggerUrl} 手动注销")
                }
            }
        } catch (e: Exception) {
            log("注销异常: ${e.message}")
            withContext(Dispatchers.Main) {
                onResult(false, "注销异常: ${e.message}，请到认证页手动注销")
            }
        }
    }

    /** 从触发 URL 派生注销地址（最佳努力） */
    private fun deriveLogoutUrl(trigger: String): String {
        val base = trigger.trimEnd('/')
        return "$base?action=logout"
    }
}
