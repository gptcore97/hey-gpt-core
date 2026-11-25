package it.flubatir.coreassistant

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Contenitore dell'app: mostra l'interfaccia web (assets/www) in una WebView e la collega ad Android. */
class MainActivity : Activity() {

    companion object {
        const val HOST = "gptcore.app"
        private const val REQ_FILE = 11
        private const val REQ_CAM = 12
        private const val BG = 0xFF0B0B10.toInt()
    }

    private lateinit var wv: WebView
    private lateinit var bridge: Bridge
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var camUri: Uri? = null

    private fun mime(p: String) = when (p.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"; "js" -> "application/javascript"; "css" -> "text/css"; "json" -> "application/json"
        "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "woff2" -> "font/woff2"; else -> "application/octet-stream"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        wv = WebView(this)
        wv.setBackgroundColor(BG)
        setContentView(wv)
        bridge = Bridge(this, wv)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        wv.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        wv.addJavascriptInterface(bridge, "Native")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host != HOST) return null
                var path = u.path ?: "/"
                if (path == "/" || path.isEmpty()) path = "/index.html"
                return try {
                    val ins = assets.open("www" + path)
                    WebResourceResponse(mime(path), "UTF-8", ins).also { it.responseHeaders = mapOf("Cache-Control" to "no-cache") }
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { super.onPageStarted(view, url, favicon) }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                val accept = p.acceptTypes.joinToString(",")
                if (p.isCaptureEnabled && accept.contains("image")) {
                    try {
                        val cv = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "gptcore_" + System.currentTimeMillis() + ".jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        }
                        camUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        if (camUri != null) {
                            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, camUri), REQ_CAM)
                            return true
                        }
                    } catch (e: Exception) { }
                }
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                i.type = if (accept.startsWith("image/") && !accept.contains(".")) "image/*" else "*/*"
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                return try { startActivityForResult(Intent.createChooser(i, "Scegli"), REQ_FILE); true }
                catch (e: Exception) { fileCb?.onReceiveValue(null); fileCb = null; false }
            }
        }

        wv.loadUrl("https://$HOST/index.html")
        handleIntent(intent, false)
    }

    private fun handleIntent(i: Intent?, live: Boolean) {
        val d = i?.data ?: return
        if (d.scheme?.startsWith("com.googleusercontent") == true) bridge.js("onUrl", d.toString())
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); handleIntent(intent, true) }

    override fun onResume() { super.onResume(); if (::bridge.isInitialized) bridge.js("onResume") }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val cb = fileCb; fileCb = null
        if (requestCode == REQ_FILE) {
            if (resultCode != RESULT_OK || data == null) { cb?.onReceiveValue(null); return }
            val list = ArrayList<Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) list.add(cd.getItemAt(k).uri) }
            if (list.isEmpty()) data.data?.let { list.add(it) }
            cb?.onReceiveValue(list.toTypedArray())
        } else if (requestCode == REQ_CAM) {
            val u = camUri; camUri = null
            if (resultCode == RESULT_OK && u != null) cb?.onReceiveValue(arrayOf(u))
            else { cb?.onReceiveValue(null); if (u != null) try { contentResolver.delete(u, null, null) } catch (e: Exception) { } }
        }
    }

    fun askPermissions() {
        val l = arrayListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(l.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        bridge.js("onPerm")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        wv.evaluateJavascript("(window.__nat&&window.__nat.back&&window.__nat.back())||false") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    override fun onDestroy() { Speaker.stop(); wv.destroy(); super.onDestroy() }
}
