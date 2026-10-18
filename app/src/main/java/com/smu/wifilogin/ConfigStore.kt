package com.smu.wifilogin

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用配置数据类。
 * 账号、密码、触发 URL、方案选择、WebView 注入选择器、注销地址均在此集中管理，
 * 全部通过 SharedPreferences 本地保存，绝不硬编码在代码逻辑里。
 */
data class AppConfig(
    val username: String = "",
    val password: String = "",
    /** 触发 URL：连接 WiFi 后访问外网会被 302 重定向到认证页。默认 http://1.1.1.1 */
    val triggerUrl: String = "http://1.1.1.1",
    /** 0 = 原生 HTTP（方案一，主方案）；1 = WebView 注入（方案二，兜底） */
    val scheme: Int = 0,
    /** 连通性验证地址，登录成功后请求以确认真正联网。默认 http://www.baidu.com */
    val verifyUrl: String = "http://www.baidu.com",
    /** 以下为 WebView 注入用 DOM 选择器，可配置、可校准 */
    val userSelector: String = "input[type=text], input[name=username], #username",
    val passSelector: String = "input[type=password], input[name=password], #password",
    val agreeSelector: String = "input[type=checkbox], #agree, input[name=accept]",
    val submitSelector: String = "button[type=submit], input[type=submit], #loginBtn, .login-btn",
    /** 注销地址：留空则按触发 URL 派生（最佳努力，详见 LogoutHelper） */
    val logoutUrl: String = ""
)

/**
 * 配置持久化工具：封装 SharedPreferences 读写。
 */
object ConfigStore {
    private const val PREF_NAME = "smu_wifi_config"
    private const val KEY_USER = "username"
    private const val KEY_PASS = "password"
    private const val KEY_TRIGGER = "trigger_url"
    private const val KEY_SCHEME = "scheme"
    private const val KEY_VERIFY = "verify_url"
    private const val KEY_USER_SEL = "user_selector"
    private const val KEY_PASS_SEL = "pass_selector"
    private const val KEY_AGREE_SEL = "agree_selector"
    private const val KEY_SUBMIT_SEL = "submit_selector"
    private const val KEY_LOGOUT = "logout_url"

    fun load(context: Context): AppConfig {
        val sp: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return AppConfig(
            username = sp.getString(KEY_USER, "") ?: "",
            password = sp.getString(KEY_PASS, "") ?: "",
            triggerUrl = sp.getString(KEY_TRIGGER, "http://1.1.1.1") ?: "http://1.1.1.1",
            scheme = sp.getInt(KEY_SCHEME, 0),
            verifyUrl = sp.getString(KEY_VERIFY, "http://www.baidu.com") ?: "http://www.baidu.com",
            userSelector = sp.getString(KEY_USER_SEL, "input[type=text], input[name=username], #username")
                ?: "input[type=text], input[name=username], #username",
            passSelector = sp.getString(KEY_PASS_SEL, "input[type=password], input[name=password], #password")
                ?: "input[type=password], input[name=password], #password",
            agreeSelector = sp.getString(KEY_AGREE_SEL, "input[type=checkbox], #agree, input[name=accept]")
                ?: "input[type=checkbox], #agree, input[name=accept]",
            submitSelector = sp.getString(KEY_SUBMIT_SEL, "button[type=submit], input[type=submit], #loginBtn, .login-btn")
                ?: "button[type=submit], input[type=submit], #loginBtn, .login-btn",
            logoutUrl = sp.getString(KEY_LOGOUT, "") ?: ""
        )
    }

    fun save(context: Context, config: AppConfig) {
        val sp: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().apply {
            putString(KEY_USER, config.username)
            putString(KEY_PASS, config.password)
            putString(KEY_TRIGGER, config.triggerUrl)
            putInt(KEY_SCHEME, config.scheme)
            putString(KEY_VERIFY, config.verifyUrl)
            putString(KEY_USER_SEL, config.userSelector)
            putString(KEY_PASS_SEL, config.passSelector)
            putString(KEY_AGREE_SEL, config.agreeSelector)
            putString(KEY_SUBMIT_SEL, config.submitSelector)
            putString(KEY_LOGOUT, config.logoutUrl)
            apply()
        }
    }
}
