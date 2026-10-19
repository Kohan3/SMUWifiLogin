package com.smu.wifilogin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 方案二：WebView 注入（备选 / 兜底）—— 智能注入版 v3（支持 iframe）
 *
 * 相比初版的改进：
 *  1. iframe 递归注入：很多校园网 Portal 把登录表单嵌在 iframe 里，
 *     主页面 querySelector 永远查不到。v3 会递归钻进所有同源 iframe 找表单。
 *  2. 账号框智能定位：选择器命中失败时，自动找「密码框前面最近的可见文本输入框」。
 *  3. 登录按钮智能定位：选择器失败时按按钮文字（登录/login/连接）查找，
 *     最后兜底提交包含密码框的表单。
 *  4. 返回结果带 frames 数量和命中位置，便于继续排查。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewAuth(
    private val webView: WebView,
    private val config: AppConfig,
    private val log: (String) -> Unit,
    private val onResult: (Boolean, String) -> Unit
) {
    private var injectedUrl: String? = null
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

                if (isSuccess(view, url)) {
                    finished = true
                    onResult(true, "登录成功（WebView 已联网）")
                    return
                }

                if (url != null && url != injectedUrl) {
                    injectedUrl = url
                    attemptInject(view)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }
        }

        log("WebView 加载触发 URL: ${config.triggerUrl}")
        webView.loadUrl(config.triggerUrl)
    }

    /** 注入 JS：递归遍历主页面 + 所有同源 iframe，智能填充账号密码并点击登录 */
    private fun attemptInject(view: WebView?) {
        val user = config.username.replace("\\", "\\\\").replace("'", "\\'")
        val pass = config.password.replace("\\", "\\\\").replace("'", "\\'")
        val userSel = config.userSelector.replace("\\", "\\\\").replace("'", "\\'")
        val passSel = config.passSelector.replace("\\", "\\\\").replace("'", "\\'")
        val agreeSel = config.agreeSelector.replace("\\", "\\\\").replace("'", "\\'")
        val submitSel = config.submitSelector.replace("\\", "\\\\").replace("'", "\\'")

        val js = """
        (function(){
            var res = {u:false, p:false, a:false, s:false, frames:0, where:''};
            var USERVAL = '$user', PASSVAL = '$pass';
            var USERSEL = '$userSel', PASSSEL = '$passSel';
            var AGREESEL = '$agreeSel', SUBMITSEL = '$submitSel';

            function visible(el){
                try{ return !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length); }
                catch(e){ return false; }
            }
            function fire(el){
                try{ el.dispatchEvent(new Event('input',{bubbles:true})); }catch(e){}
                try{ el.dispatchEvent(new Event('change',{bubbles:true})); }catch(e){}
            }
            function fillBySelector(doc, sel, val){
                try{
                    var els = doc.querySelectorAll(sel);
                    for(var i=0;i<els.length;i++){
                        if(visible(els[i])){
                            els[i].value = val;
                            try{ els[i].focus(); }catch(e){}
                            fire(els[i]);
                            return true;
                        }
                    }
                }catch(e){}
                return false;
            }
            function clickIn(doc, sel){
                try{
                    var els = doc.querySelectorAll(sel);
                    for(var i=0;i<els.length;i++){
                        if(visible(els[i])){ els[i].click(); return true; }
                    }
                }catch(e){}
                return false;
            }
            function handleDoc(doc){
                var inputs = doc.querySelectorAll('input');
                var pass = null;
                for(var i=0;i<inputs.length;i++){
                    if((inputs[i].type||'').toLowerCase()==='password' && visible(inputs[i])){
                        pass = inputs[i]; break;
                    }
                }
                if(!pass) return false;

                // ---- 账号：先按选择器，失败则找密码框前面最近的可见文本输入框 ----
                var uOk = fillBySelector(doc, USERSEL, USERVAL);
                if(!uOk){
                    var best = null;
                    for(var i=0;i<inputs.length;i++){
                        var el = inputs[i];
                        var t = (el.type||'text').toLowerCase();
                        if(t==='password'||t==='hidden'||t==='checkbox'||t==='radio'||t==='submit'||t==='button'||t==='file') continue;
                        if(!visible(el)) continue;
                        if(el.compareDocumentPosition(pass) & Node.DOCUMENT_POSITION_FOLLOWING){
                            best = el;
                        }
                    }
                    if(best){
                        best.value = USERVAL;
                        try{ best.focus(); }catch(e){}
                        fire(best);
                        uOk = true;
                    }
                }
                res.u = uOk; res.p = true;

                // ---- 同意勾选框 ----
                try{
                    if(!clickIn(doc, AGREESEL)){
                        var cbs = doc.querySelectorAll('input[type=checkbox]');
                        for(var i=0;i<cbs.length;i++){
                            if(visible(cbs[i]) && !cbs[i].checked){ cbs[i].click(); break; }
                        }
                    }
                    res.a = true;
                }catch(e){}

                // ---- 登录按钮 ----
                var sOk = clickIn(doc, SUBMITSEL);
                if(!sOk){
                    var words = ['登录','登 录','login','sign in','连接','认证'];
                    var nodes = doc.querySelectorAll('button, input[type=submit], a, div[role=button]');
                    outer:
                    for(var i=0;i<nodes.length;i++){
                        var b = nodes[i];
                        if(!visible(b)) continue;
                        var txt = (b.innerText || b.value || '').trim().toLowerCase();
                        for(var j=0;j<words.length;j++){
                            if(txt === words[j]){ b.click(); sOk = true; break outer; }
                        }
                    }
                }
                if(!sOk){
                    try{
                        var form = pass.form;
                        if(!form){
                            var fs = doc.querySelectorAll('form');
                            for(var i=0;i<fs.length;i++){
                                if(fs[i].querySelector('input[type=password]')){ form = fs[i]; break; }
                            }
                        }
                        if(form){ form.submit(); sOk = true; }
                    }catch(e){}
                }
                res.s = sOk;
                return true;
            }
            function walk(win){
                try{
                    res.frames++;
                    var doc = win.document;
                    if(handleDoc(doc)){
                        try{ res.where = win.location.href; }catch(e){ res.where = 'frame'; }
                        return true;
                    }
                    var fr = doc.querySelectorAll('iframe,frame');
                    for(var i=0;i<fr.length;i++){
                        try{
                            if(fr[i].contentWindow && walk(fr[i].contentWindow)) return true;
                        }catch(e){}
                    }
                }catch(e){}
                return false;
            }
            walk(window);
            if(!res.p){ res.where = 'no-password-found-in-' + res.frames + '-frame(s)'; }
            return JSON.stringify(res);
        })();
        """.trimIndent()

        log("注入 JS（v3 递归 iframe 版）填充账号密码并点击登录")
        log("  账号选择器=$userSel | 密码选择器=$passSel")
        log("  同意选择器=$agreeSel | 提交选择器=$submitSel")

        view?.evaluateJavascript(js) { result ->
            log("注入执行返回: $result")
            if (result != null && result.contains("\"p\":false")) {
                log("⚠️ 所有页面/iframe 里都没找到密码框（共扫描 ${result} 中 frames 字段所示数量）。请把该日志发我，或直接在 WebView 手动登录。")
            }
        }
    }

    /** 判断当前是否已登录成功 */
    private fun isSuccess(view: WebView?, url: String?): Boolean {
        if (url == null) return false
        val isPortal = url.contains("eportal", ignoreCase = true)
                || url.contains("smu.edu.cn", ignoreCase = true)
                || url.contains("1.1.1.1", ignoreCase = true)
                || url.contains("wlan", ignoreCase = true)
                || url.contains("portal", ignoreCase = true)
        if (!isPortal) {
            return true
        }
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
