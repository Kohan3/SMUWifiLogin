package com.smu.wifilogin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 方案二：WebView 注入（备选 / 兜底）
 *
 * 流程：
 *  1. 开启 JavaScript、DOM Storage、Cookie，加载触发 URL，自然跟随 302 到认证页。
 *  2. 页面加载完成后 onPageFinished 中注入 JS：填充账号密码、勾选同意框、点击登录按钮。
 *  3. 输入框/按钮的 DOM 选择器全部来自 AppConfig，可在界面“高级设置”里校准。
 *  4. 监听 URL 变化 / 页面文案判断是否登录成功。
 *  5. 若注入失败（选择器不匹配），WebView 保持可见，由用户手动点击登录。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewAuth(
    private val webView: WebView,
    private val config: AppConfig,
    private val log: (String) -> Unit,
    private val onResult: (Boolean, String) -> Unit
) {
    private var injectedUrl: String? = null   // 防止同一页面重复注入
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        finished = false
        injectedUrl = null

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                log("页面开始加载: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                log("页面加载完成: $url")
                if (finished) return

                // 成功判定：URL 已离开 Portal 域，或页面文案出现成功关键词
                if (isSuccess(view, url)) {
                    finished = true
                    onResult(true, "登录成功（WebView 已联网）")
                    return
                }

                // 仅在认证页注入一次
                if (url != null && url != injectedUrl) {
                    injectedUrl = url
                    attemptInject(view)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // 交给 WebView 自行处理重定向，不拦截
                return false
            }
        }

        log("WebView 加载触发 URL: ${config.triggerUrl}")
        webView.loadUrl(config.triggerUrl)
    }

    /** 注入 JS：填充账号密码、勾选同意、点击登录 */
    private fun attemptInject(view: WebView?) {
        val user = config.username.replace("\\", "\\\\").replace("'", "\\'")
        val pass = config.password.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
        (function(){
            function setVal(sel, val){
                try{
                    var els = document.querySelectorAll(sel);
                    if(els.length>0){
                        els[0].value = val;
                        els[0].dispatchEvent(new Event('input', {bubbles:true}));
                        els[0].dispatchEvent(new Event('change', {bubbles:true}));
                        return true;
                    }
                }catch(e){}
                return false;
            }
            function click(sel){
                try{
                    var els = document.querySelectorAll(sel);
                    if(els.length>0){ els[0].click(); return true; }
                }catch(e){}
                return false;
            }
            var u = setVal('${config.userSelector}', '$user');
            var p = setVal('${config.passSelector}', '$pass');
            var a = click('${config.agreeSelector}');   // 尝试勾选同意框
            var s = click('${config.submitSelector}');   // 点击登录按钮
            return JSON.stringify({u:u, p:p, a:a, s:s});
        })();
        """.trimIndent()

        log("注入 JS 填充账号密码并点击登录")
        log("  用户选择器=${config.userSelector}")
        log("  密码选择器=${config.passSelector}")
        log("  同意选择器=${config.agreeSelector}")
        log("  提交选择器=${config.submitSelector}")

        view?.evaluateJavascript(js) { result ->
            log("注入执行返回: $result")
            if (result == "\"{\"u\":false,\"p\":false,\"a\":false,\"s\":false}\"" || result == "{\"u\":false,\"p\":false,\"a\":false,\"s\":false}") {
                log("⚠️ 注入未命中任何元素，请到「高级设置」校准 DOM 选择器，或在下方 WebView 手动登录。")
            }
        }
    }

    /** 判断当前是否已登录成功 */
    private fun isSuccess(view: WebView?, url: String?): Boolean {
        if (url == null) return false
        // 仍在 Portal 域内（eportal / smu.edu.cn / 1.1.1.1 / wlan）视为未成功
        val isPortal = url.contains("eportal", ignoreCase = true)
                || url.contains("smu.edu.cn", ignoreCase = true)
                || url.contains("1.1.1.1", ignoreCase = true)
                || url.contains("wlan", ignoreCase = true)
        if (!isPortal) {
            // 已跳转到正常站点，基本可判定成功
            return true
        }
        // 仍在认证域，但页面可能已显示成功文案
        view?.evaluateJavascript("(document.body?document.body.innerText:'').toLowerCase()") { text ->
            val t = (text ?: "").lowercase()
            val ok = t.contains("认证成功") || t.contains("登录成功") || t.contains("上线成功")
                    || t.contains("success") || t.contains("已连接") || t.contains("欢迎") || t.contains("认证已通过")
            if (ok && !finished) {
                finished = true
                onResult(true, "登录成功（WebView 页面提示）")
            }
        }
        return false
    }
}
