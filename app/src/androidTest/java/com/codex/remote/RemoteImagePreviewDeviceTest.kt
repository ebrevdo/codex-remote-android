package com.codex.remote

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.TextView
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.remote.ui.screens.ImagePreviewView
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteImagePreviewDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersSvgImagesLocally() {
        val file = File.createTempFile("preview-test-", ".svg", composeRule.activity.cacheDir)
        file.writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><rect width="200" height="200" fill="red"/></svg>""")
        try {
            assertRedImageRendered(file, "image/svg+xml")
        } finally {
            file.delete()
        }
    }

    @Test
    fun rendersPngImagesLocally() {
        val file = File.createTempFile("preview-test-", ".png", composeRule.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            assertRedImageRendered(file, "image/png")
        } finally {
            file.delete()
        }
    }

    @Test
    fun unavailableWebViewProviderShowsFallbackInsteadOfCrashing() {
        lateinit var preview: ImagePreviewView
        composeRule.setContent {
            AndroidView(factory = { context ->
                ImagePreviewView(context, File(context.cacheDir, "unused.png"), "image/png") {
                    throw IllegalStateException("WebView provider unavailable")
                }.also { preview = it }
            }, onRelease = { it.release() })
        }
        composeRule.runOnIdle {
            assertEquals(1, preview.childCount)
            assertTrue((preview.getChildAt(0) as TextView).text.contains("Download and open"))
            preview.release()
        }
    }

    @Test
    fun rendererExitCallbackDisposesTheViewAndShowsFallback() {
        lateinit var preview: ImagePreviewView
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(factory = { context ->
                ImagePreviewView(context, File(context.cacheDir, "unused.svg"), "image/svg+xml") {
                    WebView(it).also { view -> webView = view }
                }.also { preview = it }
            }, onRelease = { it.release() })
        }
        composeRule.runOnIdle {
            val detail = object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_IMPORTANT
            }
            assertTrue(webView.webViewClient.onRenderProcessGone(webView, detail))
            assertNull(webView.parent)
            assertEquals(1, preview.childCount)
            assertTrue((preview.getChildAt(0) as TextView).text.contains("Download and open"))
            preview.release()
        }
    }

    @Test
    fun rendererTerminationRemovesTheDeadViewAndKeepsTheAppOpen() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val file = File.createTempFile("preview-renderer-", ".svg", composeRule.activity.cacheDir)
        file.writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200"><rect width="200" height="200" fill="red"/></svg>""")
        lateinit var preview: ImagePreviewView
        lateinit var webView: WebView
        try {
            composeRule.setContent {
                AndroidView(factory = { context ->
                    ImagePreviewView(context, file, "image/svg+xml") {
                        WebView(it).also { view -> webView = view }
                    }.also { preview = it }
                }, onRelease = { it.release() }, modifier = Modifier.fillMaxSize())
            }
            composeRule.waitUntil(30_000) { composeRule.runOnIdle { webView.progress == 100 && webView.webViewRenderProcess != null } }
            composeRule.runOnIdle { assertTrue(webView.webViewRenderProcess!!.terminate()) }
            composeRule.waitUntil(30_000) { composeRule.runOnIdle { preview.getChildAt(0) is TextView } }
            composeRule.runOnIdle {
                assertNull(webView.parent)
                assertTrue((preview.getChildAt(0) as TextView).text.contains("Download and open"))
                preview.release()
            }
        } finally {
            file.delete()
        }
    }

    private fun assertRedImageRendered(file: File, mimeType: String) {
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(
                factory = { context ->
                    ImagePreviewView(context, file, mimeType) {
                        WebView(it).also { view -> webView = view }
                    }
                },
                modifier = Modifier.fillMaxSize().testTag("image-test"),
                onRelease = { it.release() },
            )
        }
        composeRule.waitUntil(30_000) { composeRule.runOnIdle { webView.progress == 100 } }
        composeRule.runOnIdle {
            assertFalse(webView.settings.javaScriptEnabled)
            assertFalse(webView.settings.allowFileAccess)
            assertFalse(webView.settings.allowContentAccess)
            assertTrue(webView.settings.blockNetworkLoads)
            for (url in listOf("https://unknown.example/pixel", "file:///etc/passwd", "content://private/item")) {
                val request = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse(url)
                    override fun isForMainFrame() = false
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                assertEquals(403, webView.webViewClient.shouldInterceptRequest(webView, request)?.statusCode)
                assertTrue(webView.webViewClient.shouldOverrideUrlLoading(webView, request))
            }
        }
        val pixels = composeRule.onNodeWithTag("image-test").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("The image did not render", center.red > 0.8f && center.green < 0.2f && center.blue < 0.2f)
    }
}
