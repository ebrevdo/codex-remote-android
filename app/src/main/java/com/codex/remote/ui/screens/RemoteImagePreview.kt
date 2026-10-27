package com.codex.remote.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.codex.remote.R
import com.codex.remote.data.security.IMAGE_PREVIEW_ORIGIN
import com.codex.remote.data.security.IMAGE_PREVIEW_RESOURCE
import com.codex.remote.data.security.isLocalImageRequest
import java.io.ByteArrayInputStream
import java.io.File

@Composable
internal fun RemoteImagePreview(file: File, mimeType: String, modifier: Modifier = Modifier) {
    key(file) {
        AndroidView(
            factory = { context -> ImagePreviewView(context, file, mimeType) },
            onRelease = { view -> view.release() },
            modifier = modifier.testTag("remote-file-image"),
        )
    }
}

/** Own the WebView so startup failures and renderer exits stay inside the preview. */
@SuppressLint("ViewConstructor") // Created by Compose with a source file, never inflated from XML.
internal class ImagePreviewView(
    context: Context,
    file: File,
    mimeType: String,
    webViewFactory: (Context) -> WebView = { WebView(it) },
) : FrameLayout(context) {
    private var webView: WebView? = null

    init {
        try {
            val view = webViewFactory(context)
            webView = view
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            configureImagePreview(view, file, mimeType) { showUnavailable() }
        } catch (failure: RuntimeException) {
            Log.e("ImagePreview", "Could not initialize the image preview", failure)
            showUnavailable()
        }
    }

    fun release() {
        val view = webView ?: return
        webView = null
        removeView(view)
        view.destroy()
    }

    private fun showUnavailable() {
        release()
        addView(TextView(context).apply {
            setText(R.string.image_preview_unavailable)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}

/** The remote file is an image resource, never HTML, with no scripting or external resource access. */
private fun configureImagePreview(view: WebView, file: File, mimeType: String, onRendererGone: () -> Unit) = with(view) {
    setBackgroundColor(Color.WHITE)
    settings.apply {
        javaScriptEnabled = false
        safeBrowsingEnabled = false // All content is local; avoid network-backed URL checks.
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        domStorageEnabled = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
    }
    setOnLongClickListener { true }
    webViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e("ImagePreview", "Image renderer exited; crashed=${detail.didCrash()}")
            onRendererGone()
            return true
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            if (isLocalImageRequest(request.url.toString(), request.method)) {
                val input = runCatching { file.inputStream() }.getOrNull()
                if (input != null) return WebResourceResponse(mimeType, null, 200, "OK", mapOf(
                    "Content-Security-Policy" to "default-src 'none'; img-src data:; style-src 'unsafe-inline'",
                    "X-Content-Type-Options" to "nosniff",
                ), input)
            }
            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
    }
    val html = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src $IMAGE_PREVIEW_ORIGIN data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
        <style>html,body{margin:0;width:100%;height:100%;background:white}body{display:flex;align-items:center;justify-content:center}img{max-width:100%;max-height:100%;object-fit:contain}</style>
        </head><body><img src="$IMAGE_PREVIEW_RESOURCE" alt="Image preview unavailable. Download and open this file."></body></html>
    """.trimIndent()
    loadDataWithBaseURL(IMAGE_PREVIEW_ORIGIN, html, "text/html", "UTF-8", null)
}
