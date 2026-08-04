package com.pbp.app.export

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * HTML 로그 → PDF (서식 보존).
 *
 * 말풍선·색·명조 서술을 캔버스에 다시 그리지 않는다. **이미 만들어 둔 HTML을
 * WebView에 태워 인쇄 경로로 뽑는다** — 그러면 서식이 그대로 남고 페이지 나눔도
 * 시스템이 해 준다. 직접 그리면 앱 화면과 조용히 갈라질 코드가 한 벌 더 늘어난다.
 *
 * 인쇄 대화상자는 띄우지 않는다. [PrintDocumentAdapter]를 손으로 몰아 파일에
 * 바로 쓴다 — 사용자는 이미 저장 위치를 골랐다.
 *
 * WebView와 어댑터는 **메인 스레드 전용**이라 전 구간을 Main에서 돌린다.
 */
object PdfExporter {

    /**
     * @param destination 사용자가 고른 파일. 호출부가 열고 닫는다.
     * @return 실패 사유. 성공이면 null
     */
    suspend fun write(
        context: Context,
        html: String,
        documentName: String,
        destination: ParcelFileDescriptor,
    ): String? = withContext(Dispatchers.Main) {
        runCatching {
            val webView = loadHtml(context, html)
            val adapter = webView.createPrintDocumentAdapter(documentName)
            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                // 여백 0 — 종이 톤 배경이 가장자리까지 이어져야 화면과 같은 인상이 된다
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            layout(adapter, attributes)
            writePages(adapter, destination)
            adapter.onFinish()
            null
        }.getOrElse { it.message ?: it::class.simpleName ?: "알 수 없는 오류" }
    }

    /** 페이지가 다 그려질 때까지 기다린다 — 덜 그려진 상태로 인쇄하면 빈 장이 나온다 */
    private suspend fun loadHtml(context: Context, html: String): WebView =
        suspendCancellableCoroutine { cont ->
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = false
            webView.webViewClient = object : WebViewClient() {
                private var done = false
                override fun onPageFinished(view: WebView, url: String?) {
                    if (done) return
                    done = true
                    if (cont.isActive) cont.resume(view)
                }
            }
            // baseUrl은 null — 내장 data URI 말고는 바깥을 참조하지 않는다
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

    private suspend fun layout(adapter: PrintDocumentAdapter, attributes: PrintAttributes) =
        suspendCancellableCoroutine { cont ->
            adapter.onLayout(
                attributes,
                attributes,
                CancellationSignal(),
                object : android.print.PbpLayoutCallback() {
                    override fun onLayoutFinished(info: android.print.PrintDocumentInfo, changed: Boolean) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onLayoutFailed(error: CharSequence?) {
                        if (cont.isActive) {
                            cont.resumeWith(
                                Result.failure(IllegalStateException(error?.toString() ?: "레이아웃 실패"))
                            )
                        }
                    }
                },
                Bundle(),
            )
        }

    private suspend fun writePages(
        adapter: PrintDocumentAdapter,
        destination: ParcelFileDescriptor,
    ) = suspendCancellableCoroutine { cont ->
        adapter.onWrite(
            arrayOf(PageRange.ALL_PAGES),
            destination,
            CancellationSignal(),
            object : android.print.PbpWriteCallback() {
                override fun onWriteFinished(pages: Array<out PageRange>?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onWriteFailed(error: CharSequence?) {
                    if (cont.isActive) {
                        cont.resumeWith(
                            Result.failure(IllegalStateException(error?.toString() ?: "쓰기 실패"))
                        )
                    }
                }
            },
        )
    }
}
