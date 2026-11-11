package com.superproductivity.superproductivity.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.webkit.WebViewAssetLoader
import com.superproductivity.superproductivity.widget.ReminderDoneQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Boots the Angular engine headlessly after a Done tap on a dead process, so
 * the queued completion is persisted and synced without opening the app.
 *
 * Started from ReminderActionReceiver inside onReceive(): the broadcast's
 * temporary allowlist covers the startForegroundService() call, and the
 * foreground service is then exempt from the cached-apps freezer for its
 * lifetime — which the plain WorkManager worker was not.
 *
 * The WebView shares the Activity's IndexedDB (same origin), so the drain
 * reads and writes the same op-log the foreground app uses. The destructive
 * queue read stays inside the engine's own drain (the cold-start pull in
 * android-interface.ts): if the boot fails, the queue entry survives for the
 * next app open. The service itself only peeks.
 */
class ReminderDoneForegroundService : Service() {

    companion object {
        private const val TAG = "ReminderDoneFgService"
        private const val CHANNEL_ID = "sp_background_sync_channel"
        private const val NOTIFICATION_ID = 1002
        private const val APP_HOST = "localhost"
        // Safety net only: the engine signals when every queued done action
        // has finished (including its upload attempt). Must stay under the
        // system's ~3-minute short-service foreground limit.
        private const val COMPLETION_TIMEOUT_S = 150L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // The WebView must be created (and destroyed) on a Looper thread.
        Handler(Looper.getMainLooper()).post { bootEngine() }
    }

    private fun bootEngine() {
        val drained = CountDownLatch(1)
        val webView = createHeadlessWebView(drained)
        webView.loadUrl("https://$APP_HOST/index.html")

        // Wait off the main thread: the engine signals once per finished done
        // action (notifyBackgroundDrainComplete); the timeout is only a safety
        // net for a failed/slow boot.
        thread(name = "sp-reminder-done-drain") {
            try {
                drained.await(COMPLETION_TIMEOUT_S, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                Handler(Looper.getMainLooper()).post {
                    webView.destroy()
                    stopSelf()
                }
            }
        }
    }

    private fun createHeadlessWebView(drained: CountDownLatch): WebView {
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(APP_HOST)
            .addPathHandler("/", PublicAssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }

        webView.addJavascriptInterface(HeadlessBridge(this, drained), "SUPAndroid")
        return webView
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.superproductivity.superproductivity.R.drawable.ic_stat_sp)
            .setContentTitle("Syncing task changes…")
            .setSilent(true)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /** Maps the app origin to the Capacitor asset dir (assets/public/), as the Bridge does. */
    private class PublicAssetsPathHandler(context: android.content.Context) :
        WebViewAssetLoader.PathHandler {
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
        private val context: android.content.Context,
        private val drained: CountDownLatch,
    ) {
        private val expected = java.util.concurrent.atomic.AtomicInteger(-1)
        private val completed = java.util.concurrent.atomic.AtomicInteger(0)

        @JavascriptInterface
        fun getReminderDoneQueue(): String? {
            val raw = ReminderDoneQueue.getAndClear(context)
            if (raw != null) {
                expected.set(org.json.JSONArray(raw).length())
            }
            return raw
        }

        @JavascriptInterface
        fun notifyBackgroundDrainComplete() {
            // The engine signals once per finished done action. When every
            // queued id has been processed, the service can stop promptly.
            val expectedCount = expected.get()
            if (expectedCount < 0 || completed.incrementAndGet() >= expectedCount) {
                drained.countDown()
            }
        }
    }
}
