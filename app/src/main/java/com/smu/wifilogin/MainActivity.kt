package com.smu.wifilogin

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.smu.wifilogin.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentWebViewAuth: WebViewAuth? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 读取本地保存的配置并回填界面
        val cfg = ConfigStore.load(this)
        binding.etUsername.setText(cfg.username)
        binding.etPassword.setText(cfg.password)
        binding.etTrigger.setText(cfg.triggerUrl)
        binding.etVerify.setText(cfg.verifyUrl)
        binding.rgScheme.check(if (cfg.scheme == 1) R.id.rbWebView else R.id.rbNative)
        binding.etUserSel.setText(cfg.userSelector)
        binding.etPassSel.setText(cfg.passSelector)
        binding.etAgreeSel.setText(cfg.agreeSelector)
        binding.etSubmitSel.setText(cfg.submitSelector)
        binding.etLogout.setText(cfg.logoutUrl)

        binding.btnAdvanced.setOnClickListener {
            val v = if (binding.layoutAdvanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            binding.layoutAdvanced.visibility = v
        }

        binding.btnConnect.setOnClickListener { onConnect() }
        binding.btnLogout.setOnClickListener { onLogout() }

        appendLog("就绪。请先连接 SMU-C / SMU-C-2.4 WiFi，再点「一键连接」。")
        appendLog("提醒：认证前请关闭 VPN / 代理，否则可能认证失败。")
        appendLog("提示：主账号最多允许 3 台设备同时在线。")
    }

    /** 从界面收集当前配置 */
    private fun currentConfig(): AppConfig {
        val scheme = if (binding.rgScheme.checkedRadioButtonId == R.id.rbWebView) 1 else 0
        return AppConfig(
            username = binding.etUsername.text.toString().trim(),
            password = binding.etPassword.text.toString(),
            triggerUrl = binding.etTrigger.text.toString().trim().ifBlank { "http://1.1.1.1" },
            scheme = scheme,
            verifyUrl = binding.etVerify.text.toString().trim().ifBlank { "http://www.baidu.com" },
            userSelector = binding.etUserSel.text.toString().trim()
                .ifBlank { "input[type=text], input[name=username], #username" },
            passSelector = binding.etPassSel.text.toString().trim()
                .ifBlank { "input[type=password], input[name=password], #password" },
            agreeSelector = binding.etAgreeSel.text.toString().trim()
                .ifBlank { "input[type=checkbox], #agree, input[name=accept]" },
            submitSelector = binding.etSubmitSel.text.toString().trim()
                .ifBlank { "button[type=submit], input[type=submit], #loginBtn, .login-btn" },
            logoutUrl = binding.etLogout.text.toString().trim()
        )
    }

    private fun onConnect() {
        val cfg = currentConfig()
        if (cfg.username.isBlank() || cfg.password.isBlank()) {
            Toast.makeText(this, "请输入账号和密码", Toast.LENGTH_SHORT).show()
            return
        }
        ConfigStore.save(this, cfg)
        Toast.makeText(this, "请确认已关闭 VPN/代理，开始认证…", Toast.LENGTH_LONG).show()

        setBusy(true)
        appendLog("========== 开始认证（${if (cfg.scheme == 1) "WebView 注入" else "原生 HTTP"}）==========")

        if (cfg.scheme == 1) {
            binding.webView.visibility = View.VISIBLE
            currentWebViewAuth = WebViewAuth(
                binding.webView, cfg,
                log = { appendLog(it) },
                onResult = { ok, msg -> runOnUiThread { finishAuth(ok, msg) } }
            )
            currentWebViewAuth?.start()
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                NativeAuth.connect(
                    cfg,
                    log = { appendLog(it) },
                    onResult = { ok, msg -> runOnUiThread { finishAuth(ok, msg) } }
                )
            }
        }
    }

    private fun onLogout() {
        val cfg = currentConfig()
        ConfigStore.save(this, cfg)
        setBusy(true)
        appendLog("========== 开始注销 / 断开 ==========")
        lifecycleScope.launch(Dispatchers.IO) {
            LogoutHelper.logout(
                cfg,
                log = { appendLog(it) },
                onResult = { ok, msg ->
                    runOnUiThread {
                        setBusy(false)
                        appendLog(if (ok) "✅ $msg" else "❌ $msg")
                    }
                }
            )
        }
    }

    private fun finishAuth(ok: Boolean, msg: String) {
        setBusy(false)
        appendLog(if (ok) "✅ $msg" else "❌ $msg")
        if (!ok) {
            if (binding.rgScheme.checkedRadioButtonId != R.id.rbWebView) {
                appendLog("→ 原生方案失败，建议切到「WebView 注入」方案后再次点击连接。")
            } else {
                appendLog("→ 注入未自动成功，可直接在上方 WebView 中手动输入并点击登录。")
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnConnect.isEnabled = !busy
        binding.btnLogout.isEnabled = !busy
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            binding.tvLog.append("[$ts] $msg\n")
            binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
