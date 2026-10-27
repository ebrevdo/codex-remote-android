package com.codex.remote

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.widget.TextView
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.remote.data.security.IMAGE_PREVIEW_PAGE
import com.codex.remote.data.security.IMAGE_PREVIEW_RESOURCE
import com.codex.remote.ui.screens.ImagePreviewView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
    fun servesTheFixedPageAndImageWhileBlockingAllOtherResources() {
        val bytes = byteArrayOf(1, 2, 3)
        val file = File.createTempFile("preview-routes-", ".png", composeRule.activity.cacheDir)
        file.writeBytes(bytes)
        try {
            composeRule.runOnIdle {
                var initialUrl: String? = null
                lateinit var webView: WebView
                val preview = ImagePreviewView(composeRule.activity, file, "image/png") { context ->
                    object : WebView(context) {
                        override fun loadUrl(url: String) { initialUrl = url }
                    }.also { webView = it }
                }
                try {
                    assertEquals(IMAGE_PREVIEW_PAGE, initialUrl)
                    val client = webView.webViewClient
                    val page = checkNotNull(client.shouldInterceptRequest(webView, request(IMAGE_PREVIEW_PAGE, mainFrame = true)))
                    assertEquals(200, page.statusCode)
                    assertEquals("text/html", page.mimeType)
                    val html = page.data.bufferedReader().use { it.readText() }
                    assertTrue(html.contains("<img src=\"$IMAGE_PREVIEW_RESOURCE\""))
                    assertTrue(html.contains("default-src 'none'"))
                    val image = checkNotNull(client.shouldInterceptRequest(webView, request(IMAGE_PREVIEW_RESOURCE)))
                    assertEquals(200, image.statusCode)
                    assertEquals("image/png", image.mimeType)
                    assertArrayEquals(bytes, image.data.use { it.readBytes() })
                    val blocked = listOf(
                        request(IMAGE_PREVIEW_PAGE),
                        request(IMAGE_PREVIEW_PAGE, mainFrame = true, method = "POST"),
                        request(IMAGE_PREVIEW_RESOURCE, method = "POST"),
                        request("$IMAGE_PREVIEW_PAGE?path=/etc/passwd", mainFrame = true),
                        request("$IMAGE_PREVIEW_RESOURCE?path=/etc/passwd"),
                        request("data:text/html;charset=utf-8;base64,"),
                        request("https://unknown.example/pixel"),
                        request("file:///etc/passwd"),
                        request("content://private/item"),
                    )
                    for (resource in blocked) {
                        val response = checkNotNull(client.shouldInterceptRequest(webView, resource))
                        assertEquals(resource.url.toString(), 403, response.statusCode)
                        response.data.close()
                        assertTrue(client.shouldOverrideUrlLoading(webView, resource))
                    }
                } finally {
                    preview.release()
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingImageShowsFallbackInsteadOfABrowserErrorPage() {
        lateinit var preview: ImagePreviewView
        composeRule.setContent {
            AndroidView(factory = { context ->
                ImagePreviewView(context, File(context.cacheDir, "missing-${System.nanoTime()}.png"), "image/png")
                    .also { preview = it }
            }, onRelease = { it.release() }, modifier = Modifier.fillMaxSize())
        }
        composeRule.waitUntil(90_000) { composeRule.runOnIdle { preview.getChildAt(0) is TextView } }
        composeRule.runOnIdle {
            assertEquals(1, preview.childCount)
            assertTrue((preview.getChildAt(0) as TextView).text.contains("Download and open"))
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
                    object : WebView(it) { override fun loadUrl(url: String) {} }.also { view -> webView = view }
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
            composeRule.waitUntil(90_000) { composeRule.runOnIdle { webView.progress == 100 && webView.webViewRenderProcess != null } }
            composeRule.runOnIdle { assertTrue(webView.webViewRenderProcess!!.terminate()) }
            composeRule.waitUntil(90_000) { composeRule.runOnIdle { preview.getChildAt(0) is TextView } }
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
        composeRule.waitUntil(90_000) { composeRule.runOnIdle { webView.progress == 100 } }
        composeRule.runOnIdle {
            assertFalse(webView.settings.javaScriptEnabled)
            assertFalse(webView.settings.allowFileAccess)
            assertFalse(webView.settings.allowContentAccess)
            assertTrue(webView.settings.blockNetworkLoads)
        }
        val drawn = AtomicBoolean()
        composeRule.runOnIdle {
            assertNotNull("Preview unexpectedly fell back", webView.parent)
            assertEquals(IMAGE_PREVIEW_PAGE, webView.url)
            webView.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { drawn.set(true) }
            })
        }
        composeRule.waitUntil(90_000) { drawn.get() }
        val bounds = composeRule.runOnIdle { Rect().also { assertTrue(webView.getGlobalVisibleRect(it)) } }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        composeRule.waitUntil(90_000) {
            val screenshot = checkNotNull(automation.takeScreenshot())
            try {
                val pixel = screenshot.getPixel(bounds.centerX(), bounds.centerY())
                android.graphics.Color.red(pixel) > 200 && android.graphics.Color.green(pixel) < 50 && android.graphics.Color.blue(pixel) < 50
            } finally {
                screenshot.recycle()
            }
        }
    }

    private fun request(url: String, mainFrame: Boolean = false, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
