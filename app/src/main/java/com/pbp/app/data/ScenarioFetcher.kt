package com.pbp.app.data

import com.pbp.shared.ScenarioDoc
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 구글 독스 뷰어 문서 → 문장 목록 (V1).
 *
 * "링크가 있는 모든 사용자 – 뷰어" 문서는 export 엔드포인트가 **인증 없이** 평문을
 * 돌려준다. 그래서 API 키도 OAuth도 쓰지 않는다 — 2인 개인 앱에 키 관리 비용이
 * 과하고, 뷰어 링크라는 요구와 export 방식이 정확히 맞물린다.
 *
 * 권한이 없으면 로그인 페이지(HTML)로 떨어지므로 **본문이 HTML이면 권한 없음**으로
 * 읽는다. 상태 코드만 보면 200 OK인 로그인 페이지를 문서로 착각한다.
 *
 * 앱에는 범용 HTTP 클라이언트가 없다(Firestore SDK는 자체 통신). 이것 하나 때문에
 * 의존성을 늘리지 않고 [HttpURLConnection]으로 끝낸다.
 */
object ScenarioFetcher {

    sealed interface Result {
        /**
         * @param title 문서 제목 — 응답 헤더에서 못 뽑으면 null (화면이 대체 문구를 쓴다)
         * @param truncated 상한에 걸려 뒷부분을 버렸는가 — 화면이 알려 준다 (K3)
         */
        data class Ok(
            val title: String?,
            val sentences: List<String>,
            val truncated: Boolean = false,
        ) : Result

        enum class Error : Result {
            /** 구글 독스 문서 링크가 아니다 */
            BAD_LINK,

            /** 링크는 맞는데 열리지 않는다 — 공유 설정 문제 */
            NO_ACCESS,

            /** 연결 실패·타임아웃, 그리고 서버 쪽 일시 장애(5xx·429) */
            NETWORK,

            /** 열리긴 했는데 표시할 문장이 없다 */
            EMPTY,
        }
    }

    private const val TIMEOUT_MS = 10_000

    /** 본문 상한 — 시나리오 텍스트로 충분하고, 넘치는 문서에 OOM으로 죽지 않게 */
    private const val MAX_BYTES = 1024 * 1024

    /** **[kotlinx.coroutines.Dispatchers.IO]에서 부를 것** — 호출부(V2)가 감싼다 */
    fun fetch(url: String): Result {
        val docId = ScenarioDoc.extractDocId(url) ?: return Result.Error.BAD_LINK
        val connection = try {
            (URL(ScenarioDoc.exportUrl(docId)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // 리다이렉트는 기본 추종 — export는 1~2회 경유한다
                instanceFollowRedirects = true
            }
        } catch (_: IOException) {
            return Result.Error.NETWORK
        }
        val title: String?
        val body: ByteArray
        val truncated: Boolean
        try {
            val code = connection.responseCode
            // 5xx·429는 공유 설정과 무관한 일시 장애다 — "공유 설정을 확인하세요"라고
            // 안내하면 고칠 수 없는 것을 고치라고 시키는 셈이 된다 (K3)
            if (code >= 500 || code == 429) return Result.Error.NETWORK
            if (code !in 200..299) return Result.Error.NO_ACCESS
            // 제목은 첨부 파일 이름에 실려 온다 — 본문 첫 줄을 제목이라 우기지 않는다
            title = ScenarioDoc.titleFromDisposition(connection.getHeaderField("Content-Disposition"))
            // 상한보다 1바이트 더 읽어 "넘쳤는지"를 안다 — 딱 맞게 읽으면 잘렸는지
            // 알 길이 없어 조용히 버리게 된다 (K3)
            val read = connection.inputStream.use { readAtMost(it, MAX_BYTES + 1) }
            truncated = read.size > MAX_BYTES
            body = if (truncated) read.copyOf(MAX_BYTES) else read
        } catch (_: IOException) {
            return Result.Error.NETWORK
        } finally {
            connection.disconnect()
        }
        // UTF-8 고정 — export가 돌려주는 인코딩이다. BOM은 여기서 뗀다:
        // 남겨 두면 첫 문장에 안 보이는 글자가 붙고 HTML 판별도 빗나간다
        val text = ScenarioDoc.stripBom(
            String(body, 0, completeUtf8Length(body), Charsets.UTF_8)
        )
        // 200인데 HTML이면 로그인 페이지다 — 문서로 착각하면 목차가 통째로 문장이 된다
        if (text.trimStart().startsWith("<")) return Result.Error.NO_ACCESS
        val sentences = ScenarioDoc.splitSentences(text)
        return if (sentences.isEmpty()) Result.Error.EMPTY
        else Result.Ok(title, sentences, truncated)
    }

    /**
     * 최대 [limit]바이트까지 읽는다.
     *
     * `InputStream.readNBytes`를 쓰지 않는다 — **API 33에 추가된 메서드**라 minSdk 26인
     * 이 앱에서는 구형 기기에서 `NoSuchMethodError`로 즉사한다. Error라 IOException
     * catch에도 걸리지 않아 그대로 크래시였다 (G1).
     */
    private fun readAtMost(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /**
     * 온전한 UTF-8 문자까지의 길이. 상한에서 자르면 한글 한 글자가 두 동강 나
     * 마지막 문장에 U+FFFD가 남는다 — 잘린 꼬리는 아예 버린다 (K3).
     */
    private fun completeUtf8Length(bytes: ByteArray): Int {
        // 이어지는 바이트(10xxxxxx)를 거슬러 올라가 선두 바이트를 찾는다.
        // UTF-8 문자는 최대 4바이트라 세 걸음이면 충분하다
        var i = bytes.size - 1
        var trailing = 0
        while (i >= 0 && trailing < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
            i--
            trailing++
        }
        if (i < 0) return bytes.size
        val lead = bytes[i].toInt() and 0xFF
        val needed = when {
            lead < 0x80 -> 1
            lead in 0xC0..0xDF -> 2
            lead in 0xE0..0xEF -> 3
            lead in 0xF0..0xF7 -> 4
            else -> return bytes.size // 선두가 아니면 판단 포기 — 있는 그대로
        }
        // 선두 + 이어지는 바이트가 모자라면 그 문자를 통째로 버린다
        return if (trailing + 1 == needed) bytes.size else i
    }
}
