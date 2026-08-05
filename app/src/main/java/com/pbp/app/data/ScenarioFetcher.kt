package com.pbp.app.data

import com.pbp.shared.ScenarioDoc
import java.io.IOException
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
        /** @param title 문서 제목 — 응답 헤더에서 못 뽑으면 null (화면이 대체 문구를 쓴다) */
        data class Ok(val title: String?, val sentences: List<String>) : Result

        enum class Error : Result {
            /** 구글 독스 문서 링크가 아니다 */
            BAD_LINK,

            /** 링크는 맞는데 열리지 않는다 — 공유 설정 문제 */
            NO_ACCESS,

            /** 연결 실패·타임아웃 */
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
        val text = try {
            if (connection.responseCode !in 200..299) return Result.Error.NO_ACCESS
            // 제목은 첨부 파일 이름에 실려 온다 — 본문 첫 줄을 제목이라 우기지 않는다
            title = ScenarioDoc.titleFromDisposition(connection.getHeaderField("Content-Disposition"))
            connection.inputStream.use { stream ->
                // UTF-8 고정 — export가 돌려주는 인코딩이다. BOM은 여기서 뗀다:
                // 남겨 두면 첫 문장에 안 보이는 글자가 붙고 HTML 판별도 빗나간다
                ScenarioDoc.stripBom(String(stream.readNBytes(MAX_BYTES), Charsets.UTF_8))
            }
        } catch (_: IOException) {
            return Result.Error.NETWORK
        } finally {
            connection.disconnect()
        }
        // 200인데 HTML이면 로그인 페이지다 — 문서로 착각하면 목차가 통째로 문장이 된다
        if (text.trimStart().startsWith("<")) return Result.Error.NO_ACCESS
        val sentences = ScenarioDoc.splitSentences(text)
        return if (sentences.isEmpty()) Result.Error.EMPTY else Result.Ok(title, sentences)
    }
}
