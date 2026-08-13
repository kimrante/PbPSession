package com.pbp.desktop.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pbp.desktop.GOOGLE_CLIENT_SECRET
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PC 구글 로그인.
 *
 * 데스크톱에는 Firebase SDK가 없어 손으로 두 번 교환한다:
 *   ① 브라우저에서 구글에 로그인 → 우리가 띄운 127.0.0.1 서버로 인가 코드가 돌아온다
 *   ② 코드를 구글 토큰 엔드포인트에서 ID 토큰으로, 그 ID 토큰을 다시 Firebase에서
 *      이 프로젝트의 계정(uid·리프레시 토큰)으로 바꾼다
 *
 * 설치형 앱이라 시크릿을 완전히 숨길 수 없다 — 구글도 그 전제로 설계돼 있어
 * **PKCE**(코드 검증기)를 함께 쓴다. 시크릿만으로는 코드를 교환하지 못한다.
 */
object GoogleSignIn {

    /** GCP 콘솔의 '데스크톱 앱' OAuth 클라이언트. 비밀이 아니라 식별자다 */
    private const val CLIENT_ID =
        "573066454943-ebrb7fl2skcmbn7vngfh7lc5jpbkc5cn.apps.googleusercontent.com"

    /** 시크릿이 빌드에 들어오지 않았으면 로그인 항목 자체를 감춘다 */
    val isConfigured: Boolean get() = GOOGLE_CLIENT_SECRET.isNotEmpty()

    /** 브라우저를 열어 두고 기다리는 시간 — 계정 선택·2단계 인증까지 넉넉히 */
    private val WAIT = TimeUnit.MINUTES.toMillis(3)

    data class Account(val uid: String, val idToken: String, val refreshToken: String, val email: String?)

    sealed interface Result {
        data class Ok(val account: Account) : Result
        /** 브라우저를 닫았거나 시간이 지났다 — 실패로 요란하게 알릴 일이 아니다 */
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()

    /**
     * 브라우저로 로그인시키고 이 프로젝트의 계정을 받아 온다. **호출 스레드를 막는다** — IO에서 부를 것.
     *
     * @param apiKey Firebase 웹 API 키 (모바일과 같은 값)
     */
    fun signIn(apiKey: String): Result {
        if (!isConfigured) return Result.Failed("구글 로그인이 이 빌드에 설정돼 있지 않습니다")
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(24)

        // 포트는 OS가 고르게 둔다(0) — 고정하면 다른 프로그램이 쓰고 있을 때 로그인이 막힌다
        val server = runCatching { HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0) }
            .getOrElse { return Result.Failed("로그인 창을 열지 못했습니다: ${it.message}") }
        val redirectUri = "http://127.0.0.1:${server.address.port}"
        // 콜백은 서버 스레드에서 오고 우리는 여기서 기다린다 — 큐 하나로 건네받는다
        val received = ArrayBlockingQueue<Map<String, String>>(1)

        server.createContext("/") { exchange ->
            val query = parseQuery(exchange.requestURI.rawQuery)
            val page = if (query["code"] != null) {
                "로그인이 끝났습니다. 이 창을 닫고 PbP로 돌아가세요."
            } else {
                "로그인이 취소되었습니다. 이 창을 닫아도 됩니다."
            }
            val bytes = """
                <!doctype html><meta charset="utf-8">
                <body style="font-family:system-ui;padding:48px;text-align:center">$page</body>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            runCatching {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            received.offer(query)
        }
        server.start()

        try {
            val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=${enc(CLIENT_ID)}" +
                "&redirect_uri=${enc(redirectUri)}" +
                "&response_type=code" +
                "&scope=${enc("openid email profile")}" +
                "&code_challenge=${enc(challengeOf(verifier))}" +
                "&code_challenge_method=S256" +
                "&state=${enc(state)}" +
                // 계정이 여러 개일 수 있다 — 늘 고르게 한다
                "&prompt=select_account"
            if (!openBrowser(authUrl)) {
                return Result.Failed("브라우저를 열지 못했습니다. 주소를 직접 여세요:\n$authUrl")
            }

            val callback = received.poll(WAIT, TimeUnit.MILLISECONDS) ?: return Result.Cancelled
            if (callback["state"] != state) return Result.Failed("로그인 응답이 일치하지 않습니다")
            val code = callback["code"] ?: return Result.Cancelled

            val googleIdToken = exchangeCode(code, verifier, redirectUri)
                ?: return Result.Failed("구글 토큰 교환에 실패했습니다")
            return signInToFirebase(googleIdToken, apiKey)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.Cancelled
        } finally {
            server.stop(0)
        }
    }

    /** 인가 코드 → 구글 ID 토큰 */
    private fun exchangeCode(code: String, verifier: String, redirectUri: String): String? {
        val form = mapOf(
            "code" to code,
            "client_id" to CLIENT_ID,
            "client_secret" to GOOGLE_CLIENT_SECRET,
            "code_verifier" to verifier,
            "grant_type" to "authorization_code",
            "redirect_uri" to redirectUri,
        ).entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return postJson("https://oauth2.googleapis.com/token", form, form = true)
            ?.get("id_token")?.asString
    }

    /** 구글 ID 토큰 → 이 Firebase 프로젝트의 계정 */
    private fun signInToFirebase(googleIdToken: String, apiKey: String): Result {
        val body = JsonObject().apply {
            addProperty("postBody", "id_token=$googleIdToken&providerId=google.com")
            // 실제로 요청이 오간 주소가 아니어도 된다 — 형식만 맞으면 되는 필드다
            addProperty("requestUri", "http://127.0.0.1")
            addProperty("returnSecureToken", true)
        }
        val res = postJson(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$apiKey",
            body.toString(),
        ) ?: return Result.Failed("Firebase 로그인에 실패했습니다")
        val uid = res.get("localId")?.asString ?: return Result.Failed("계정 정보를 받지 못했습니다")
        return Result.Ok(
            Account(
                uid = uid,
                idToken = res.get("idToken")?.asString ?: return Result.Failed("토큰을 받지 못했습니다"),
                refreshToken = res.get("refreshToken")?.asString
                    ?: return Result.Failed("토큰을 받지 못했습니다"),
                email = res.get("email")?.asString,
            )
        )
    }

    private fun postJson(url: String, body: String, form: Boolean = false): JsonObject? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(30))
            .header(
                "Content-Type",
                if (form) "application/x-www-form-urlencoded" else "application/json",
            )
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            System.err.println("구글 로그인 응답 ${response.statusCode()}: ${response.body().take(300)}")
            return null
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrNull()

    private fun openBrowser(url: String): Boolean = runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) return false
        desktop.browse(URI.create(url))
        true
    }.getOrDefault(false)

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null else {
                val key = java.net.URLDecoder.decode(pair.take(index), StandardCharsets.UTF_8)
                val value = java.net.URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8)
                key to value
            }
        }.toMap()

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes)
        .also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun challengeOf(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )
}
