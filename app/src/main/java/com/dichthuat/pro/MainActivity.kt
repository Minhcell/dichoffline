package com.dichthuat.pro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var prefs: SharedPreferences

    private val AUDIO_PERMISSION_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("DichThuatPro", Context.MODE_PRIVATE)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        // Xin quyền micro
        requestAudioPermission()

        // Setup WebView
        setupWebView()

        // Load server URL
        val serverUrl = getServerUrl()
        loadServer(serverUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true

            // User Agent
            userAgentString = "$userAgentString DichThuatPro/3.0"
        }

        // Cho phép ghi âm
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    showErrorPage()
                }
            }
        }
    }

    private fun getServerUrl(): String {
        // Ưu tiên URL đã lưu
        val saved = prefs.getString("server_url", null)
        if (!saved.isNullOrEmpty()) return saved

        // Mặc định từ BuildConfig
        return BuildConfig.SERVER_URL
    }

    private fun loadServer(url: String) {
        if (isNetworkAvailable()) {
            webView.loadUrl(url)
        } else {
            showErrorPage()
        }
    }

    private fun showErrorPage() {
        val currentUrl = getServerUrl()
        val deviceIp = getDeviceIp()

        val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    background: #0f172a; color: #f1f5f9;
                    font-family: sans-serif;
                    display: flex; align-items: center; justify-content: center;
                    min-height: 100vh; margin: 0; padding: 20px;
                    text-align: center;
                }
                .card {
                    background: #1e293b; border-radius: 16px;
                    padding: 32px; max-width: 400px; width: 100%;
                    box-shadow: 0 4px 24px rgba(0,0,0,0.5);
                }
                h2 { color: #38bdf8; margin-bottom: 8px; }
                p { color: #94a3b8; line-height: 1.6; }
                .ip-box {
                    background: #0f172a; border: 1px solid #374151;
                    border-radius: 8px; padding: 12px; margin: 16px 0;
                    font-family: monospace; font-size: 14px; color: #38bdf8;
                }
                input {
                    width: 100%; padding: 12px; border-radius: 8px;
                    border: 1px solid #374151; background: #0f172a;
                    color: #f1f5f9; font-size: 16px; margin: 8px 0;
                    box-sizing: border-box;
                }
                .btn {
                    display: block; width: 100%; padding: 14px;
                    border: none; border-radius: 12px;
                    font-size: 16px; font-weight: 600;
                    cursor: pointer; margin: 8px 0;
                }
                .btn-primary { background: #38bdf8; color: #0f172a; }
                .btn-retry { background: #334155; color: #f1f5f9; }
                .hint { font-size: 12px; color: #64748b; margin-top: 16px; }
            </style>
        </head>
        <body>
            <div class="card">
                <h2>⚠️ Không kết nối được</h2>
                <p>Kiểm tra server đang chạy trên PC và cùng mạng WiFi</p>

                <div class="ip-box">
                    📱 IP điện thoại: $deviceIp<br>
                    🖥️ Server hiện tại: $currentUrl
                </div>

                <input id="serverInput" type="url" placeholder="http://192.168.1.x:5000"
                       value="$currentUrl">

                <button class="btn btn-primary" onclick="saveAndConnect()">
                    🔗 Kết nối Server
                </button>

                <button class="btn btn-retry" onclick="location.reload()">
                    🔄 Thử lại
                </button>

                <div class="hint">
                    💡 Trên PC chạy: python app.py<br>
                    Rồi nhập IP của PC vào ô trên
                </div>
            </div>
            <script>
                function saveAndConnect() {
                    var url = document.getElementById('serverInput').value.trim();
                    if (url) {
                        // Gửi URL cho Android qua JS interface
                        if (window.AndroidBridge) {
                            window.AndroidBridge.saveServerUrl(url);
                        }
                        window.location.href = url;
                    }
                }
            </script>
        </body>
        </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)

        // JS Bridge để nhận URL từ trang lỗi
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun saveServerUrl(url: String) {
                prefs.edit().putString("server_url", url).apply()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Đã lưu: $url", Toast.LENGTH_SHORT).show()
                }
            }
        }, "AndroidBridge")
    }

    // ── Quyền Microphone ──
    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ Đã cho phép ghi âm", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Utils ──
    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceIp(): String {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (e: Exception) {
            return "Không xác định"
        }
    }

    // ── Nút Back ──
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // Hỏi trước khi thoát
            AlertDialog.Builder(this)
                .setTitle("Thoát ứng dụng?")
                .setPositiveButton("Thoát") { _, _ -> super.onBackPressed() }
                .setNegativeButton("Ở lại", null)
                .show()
        }
    }

    // ── Menu cài đặt (long press title) ──
    fun openSettings() {
        val input = android.widget.EditText(this)
        input.setText(getServerUrl())
        input.hint = "http://192.168.1.x:5000"

        AlertDialog.Builder(this)
            .setTitle("🖥️ Đổi Server IP")
            .setView(input)
            .setPositiveButton("Lưu & Kết nối") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    prefs.edit().putString("server_url", url).apply()
                    loadServer(url)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}
