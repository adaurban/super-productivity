package com.superproductivity.superproductivity.service

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.superproductivity.superproductivity.widget.ReminderDoneQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Boots the app's Angular engine headlessly (no Activity) so the existing
 * drain effects process queued reminder Done completions and sync them, after
 * a tap on a dead process (ReminderActionReceiver enqueues this worker).
 *
 * The engine is the only writer that can produce a correct completion op
 * (recurrence, vector clocks, op-log capture, provider upload all live there),
 * so this worker deliberately reuses it instead of writing ops natively. The
 * WebView shares the Activity's IndexedDB (same origin), so the drain reads
 * and writes the same op-log the foreground app uses — no second state store.
 *
 * The destructive queue read stays inside the engine's own drain (the
 * cold-start pull in android-interface.ts): if the boot fails, the queue
 * entry survives for the next app open. The worker itself only peeks.
 */
class ReminderDoneWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ReminderDoneWorker"
        private const val APP_HOST = "localhost"
        private const val DRAIN_TIMEOUT_S = 60L
    }

    override suspend fun doWork(): Result {
        // Peek only: booting the engine for an empty queue is pure waste, and
        // the destructive read belongs to the engine's drain, not to us.
        if (!ReminderDoneQueue.hasPending(applicationContext)) {
            return Result.success()
        }

        val drained = CountDownLatch(1)
        var webView: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                webView = createHeadlessWebView(applicationContext, drained)
                webView?.loadUrl("https://$APP_HOST/index.html")
            }
            // The engine signals via notifyBackgroundDrainComplete() once its
            // drain has run; the timeout only bounds a failed/slow boot.
            drained.await(DRAIN_TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            withContext(Dispatchers.Main) { webView?.destroy() }
        }
        return Result.success()
    }

    private fun createHeadlessWebView(context: Context, drained: CountDownLatch): WebView {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(APP_HOST)
            .addPathHandler("/", PublicAssetsPathHandler(context))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }

        webView.addJavascriptInterface(HeadlessBridge(context, drained), "SUPAndroid")
        return webView
    }

    /** Maps the app origin to the Capacitor asset dir (assets/public/), as the Bridge does. */
    private class PublicAssetsPathHandler(context: Context) : WebViewAssetLoader.PathHandler {
        private val delegate = WebViewAssetLoader.AssetsPathHandler(context)
        override fun handle(path: String): WebResourceResponse? =
            delegate.handle(if (path.isEmpty()) "public/index.html" else "public/$path")
    }

    /**
     * The minimal native surface the drain needs. The full JavaScriptInterface
     * is Activity-bound; the headless boot only reads the queue and receives
     * the completion signal.
     */
    private class HeadlessBridge(
        private val context: Context,
        private val drained: CountDownLatch,
    ) {
        @JavascriptInterface
        fun getReminderDoneQueue(): String? = ReminderDoneQueue.getAndClear(context)

        @JavascriptInterface
        fun notifyBackgroundDrainComplete() {
            drained.countDown()
        }
    }
}
