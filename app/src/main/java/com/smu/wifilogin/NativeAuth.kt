package com.smu.wifilogin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * 方案一：原生 HTTP 认证（主方案）
 *
 * 流程：
 *  1. OkHttp 请求触发 URL（http://1.1.1.1 或 http://rz.smu.edu.cn），关闭自动重定向以捕获 302 Location。
 *  2. 跟随 Location 拿到“当前会话真实认证地址”（设备 IP 会变，绝不硬编码）。
 *  3. 用 Jsoup 解析认证页，提取表单 action 与所有隐藏字段（wlanuserip、wlanacname 等）。
 *  4. 构造 POST 表单，填入账号密码与解析出的参数并提交。
 *  5. 根据响应 + 连通性验证（二次请求触发 URL / 百度）判断成功。
 *
 * 全部在 IO 协程中执行，不阻塞 UI。
 */
object NativeAuth {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 不跟随重定向的客户端：用于捕获 302 Location */
    private fun noFollowClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 跟随重定向的客户端：用于拉取认证页、提交、验证 */
    private fun followClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 执行认证。
     * @param config     当前配置
     * @param log        日志回调（线程安全由调用方保证切回主线程）
     * @param onResult   结果回调：success 是否成功，message 原因
     */
    suspend fun connect(
        config: AppConfig,
        log: (String) -> Unit,
        onResult: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val clientNo = noFollowClient()
            val clientYes = followClient()

            // ---- 第 1 步：请求触发 URL，捕获 302 ----
            log("【原生方案】请求触发 URL: ${config.triggerUrl}")
            val triggerReq = Request.Builder()
                .url(config.triggerUrl)
                .header("User-Agent", UA)
                .get()
                .build()
            val triggerResp = clientNo.newCall(triggerReq).execute()
            val authUrl: String = if (triggerResp.isRedirect) {
                val loc = triggerResp.header("Location")
                triggerResp.close()
                if (loc.isNullOrBlank()) {
                    log("触发 URL 返回 302 但无 Location，回退直接访问触发页本身")
                    config.triggerUrl
                } else {
                    log("捕获 302 Location: $loc")
                    resolveUrl(config.triggerUrl, loc)
                }
            } else {
                log("触发 URL 未重定向(可能已联网或当前网络异常)，尝试直接访问该地址")
                triggerResp.close()
                config.triggerUrl
            }

            // ---- 第 2 步：拉取认证页 HTML ----
            log("获取认证页: $authUrl")
            val pageReq = Request.Builder().url(authUrl).header("User-Agent", UA).get().build()
            val (_, pageHtml) = clientYes.newCall(pageReq).execute().use { resp ->
                resp.code to (resp.body?.string().orEmpty())
            }
            if (pageHtml.isBlank()) {
                log("认证页内容为空，无法解析表单")
                withContext(Dispatchers.Main) {
                    onResult(false, "认证页为空，请切换到方案二(WebView)")
                }
                return@withContext
            }

            // ---- 第 3 步：Jsoup 解析表单 ----
            log("使用 Jsoup 解析隐藏表单字段（wlanuserip / wlanacname 等）...")
            val (postUrl, fields) = parseForm(pageHtml, authUrl, config)
            if (postUrl == null) {
                log("未能解析到表单提交地址，Jsoup 解析失败")
                withContext(Dispatchers.Main) {
                    onResult(false, "Jsoup 解析不到表单，请切换到方案二(WebView)")
                }
                return@withContext
            }
            log("提交地址: $postUrl")
            log("表单字段: " + fields.joinToString(", ") { "${it.first}=${it.second}" })

            // ---- 第 4 步：POST 提交 ----
            val fb = FormBody.Builder()
            for ((k, v) in fields) fb.add(k, v)
            val postReq = Request.Builder()
                .url(postUrl)
                .header("User-Agent", UA)
                .header("Referer", authUrl)
                .header("Origin", extractOrigin(authUrl))
                .post(fb.build())
                .build()
            val postResp = clientYes.newCall(postReq).execute()
            val postCode = postResp.code
            val postBody = postResp.use { it.body?.string().orEmpty() }
            log("认证响应码: $postCode")
            log("认证响应片段: ${postBody.take(400)}")

            // ---- 第 5 步：判定 + 连通性验证 ----
            val respSuggestsOk = isSuccessResponse(postBody) || !postResp.isRedirect
            val online = verifyOnline(clientNo, clientYes, config, log)

            withContext(Dispatchers.Main) {
                when {
                    online -> onResult(true, "认证成功，已联网（连通性验证通过）")
                    respSuggestsOk -> onResult(
                        true,
                        "表单已提交且响应正常，但连通性验证未确认，请自行打开网页确认"
                    )
                    else -> onResult(false, "认证未成功，响应片段：${postBody.take(200)}")
                }
            }
        } catch (e: Exception) {
            log("原生方案异常: ${e.message}")
            withContext(Dispatchers.Main) {
                onResult(false, "异常：${e.message}，建议切换到方案二(WebView)")
            }
        }
    }

    /**
     * 解析认证页表单：返回 (提交地址, 字段列表)。
     * 规则：
     *  - 优先取 <form> 的 abs:action，否则用当前页 URL。
     *  - 遍历所有 <input>：hidden 直接带原值；password 填配置密码；text/email 填配置账号；
     *    checkbox 仅当默认 checked 时带上；submit/button 跳过。
     *  - 若未识别到账号/密码字段，补默认 username/password。
     */
    private fun parseForm(
        html: String,
        baseUrl: String,
        config: AppConfig
    ): Pair<String?, List<Pair<String, String>>> {
        val doc = Jsoup.parse(html, baseUrl)
        val form = doc.selectFirst("form")
        val inputs = form?.select("input") ?: doc.select("input")
        val action = form?.attr("abs:action")?.takeIf { it.isNotBlank() } ?: baseUrl

        val fields = mutableListOf<Pair<String, String>>()
        val added = mutableSetOf<String>()
        var userField: String? = null
        var passField: String? = null

        for (el in inputs) {
            val name = el.attr("name")
            if (name.isBlank()) continue
            val type = el.attr("type").lowercase()
            val value = el.attr("value")
            when (type) {
                "submit", "button", "image" -> { /* 不提交按钮 */ }
                "password" -> {
                    fields.add(name to config.password)
                    added.add(name)
                    passField = name
                }
                "text", "email", "tel" -> {
                    fields.add(name to config.username)
                    added.add(name)
                    userField = name
                }
                "checkbox" -> {
                    if (el.hasAttr("checked")) {
                        fields.add(name to value.ifBlank { "on" })
                        added.add(name)
                    }
                }
                else -> {
                    // hidden / radio(未勾选) 等，原样带上
                    fields.add(name to value)
                    added.add(name)
                }
            }
        }

        // 兜底：未识别到账号/密码字段时补常用名
        if (userField == null) {
            fields.add("username" to config.username)
            added.add("username")
        }
        if (passField == null) {
            fields.add("password" to config.password)
            added.add("password")
        }

        return action to fields
    }

    /** 根据响应文本粗判是否成功（不同厂商 Portal 文案不同，做多关键词匹配） */
    private fun isSuccessResponse(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("认证成功") || b.contains("登录成功") || b.contains("上线成功")
                || b.contains("success") || b.contains("login success") || b.contains("welcome")
                || b.contains("已连接") || b.contains("connect success") || b.contains("认证已通过")
    }

    /**
     * 连通性验证：
     *  主：再次请求触发 URL（已联网则不再 302 重定向）→ 通过。
     *  备：请求 verifyUrl（默认百度），返回 200 且含 "baidu" → 通过。
     */
    private fun verifyOnline(
        clientNo: OkHttpClient,
        clientYes: OkHttpClient,
        config: AppConfig,
        log: (String) -> Unit
    ): Boolean {
        return try {
            val reReq = Request.Builder().url(config.triggerUrl).header("User-Agent", UA).get().build()
            val reResp = clientNo.newCall(reReq).execute()
            val notRedirected = !reResp.isRedirect
            reResp.close()
            if (notRedirected) {
                log("连通性验证(触发 URL 不再重定向)：通过 ✅")
                return true
            }
            // 二次验证：百度
            val baiduReq = Request.Builder().url(config.verifyUrl).header("User-Agent", UA).get().build()
            val baiduResp = clientYes.newCall(baiduReq).execute()
            val body = baiduResp.use { it.body?.string().orEmpty() }
            val ok = baiduResp.isSuccessful && body.contains("baidu", ignoreCase = true)
            log("连通性验证(百度)：${if (ok) "通过 ✅" else "未通过 ❌"}")
            ok
        } catch (e: Exception) {
            log("连通性验证异常: ${e.message}")
            false
        }
    }

    /** 将相对地址解析为绝对地址 */
    private fun resolveUrl(base: String, relative: String): String {
        return try {
            val baseU = base.toHttpUrlOrNull() ?: return relative
            baseU.resolve(relative).toString()
        } catch (e: Exception) {
            relative
        }
    }

    private fun extractOrigin(url: String): String {
        return try {
            val u = url.toHttpUrlOrNull() ?: return url
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            url
        }
    }
}
