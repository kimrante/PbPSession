package com.pbp.desktop.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/** 메시지 (Firestore 문서 ↔ 로컬 모델) — 안드로이드 앱과 같은 스키마 */
data class Message(
    val docId: String,
    val type: String,
    val body: String,
    val diceExpr: String?,
    val senderName: String?,
    val senderEmoji: String?,
    val senderIsGm: Boolean,
    val senderIsBot: Boolean,
    val senderNameColor: Long?,
    val senderBubbleColor: Long?,
    val isOoc: Boolean,
    val editedAt: Long?,
    val createdAt: Long,
    val authorUid: String,
    val avatarId: String?,
)

data class RoomMeta(
    val remoteId: String,
    val name: String,
    val icon: String,
    val inviteCode: String?,
    val themeColor: Long,
    val backgroundKey: String,
)

/**
 * Firestore REST 클라이언트 (모바일과 같은 프로젝트/스키마).
 * 데스크톱은 공식 클라이언트 SDK가 없어 REST + 폴링으로 동기화한다.
 */
class FirestoreRest(
    private val projectId: String,
    private val apiKey: String,
    initialRefreshToken: String? = null,
    /** 익명 계정 발급/갱신 시 리프레시 토큰을 보존하기 위한 콜백 */
    private val onAuthChanged: (refreshToken: String, uid: String) -> Unit = { _, _ -> },
) {
    private val base =
        "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"

    // 타임아웃 없는 기본 클라이언트는 TCP 블랙홀 시 폴링이 영구 정지한다 (P2-8)
    private val http = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
    private val requestTimeout = java.time.Duration.ofSeconds(30)
    private val gson = Gson()

    // ── 익명 인증 (P0-1) — 모든 요청에 Bearer ID 토큰을 붙인다 ──
    @Volatile private var idToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0

    /** 익명 auth UID — 멤버 문서 키. 인증 전이면 null */
    @Volatile var uid: String? = null
        private set

    @Volatile private var refreshToken: String? = initialRefreshToken

    @Synchronized
    private fun currentToken(): String? {
        idToken?.let { if (System.currentTimeMillis() < tokenExpiresAt - 60_000) return it }
        refreshToken?.let { if (refreshIdToken(it)) return idToken }
        if (signUpAnonymous()) return idToken
        return null // 인증 실패 — 보안 규칙 배포 전의 공개 모드에서만 동작 가능
    }

    private fun signUpAnonymous(): Boolean = runCatching {
        val res = http.send(
            HttpRequest.newBuilder(URI.create("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey")).timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"returnSecureToken":true}""", StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (res.statusCode() !in 200..299) return false
        val o = JsonParser.parseString(res.body()).asJsonObject
        applyTokens(
            token = o.get("idToken")?.asString ?: return false,
            newUid = o.get("localId")?.asString ?: return false,
            newRefresh = o.get("refreshToken")?.asString ?: return false,
            expiresInSec = o.get("expiresIn")?.asString?.toLongOrNull() ?: 3600,
        )
        true
    }.getOrDefault(false)

    private fun refreshIdToken(token: String): Boolean = runCatching {
        val res = http.send(
            HttpRequest.newBuilder(URI.create("https://securetoken.googleapis.com/v1/token?key=$apiKey")).timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=refresh_token&refresh_token=$token", StandardCharsets.UTF_8,
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (res.statusCode() !in 200..299) return false
        val o = JsonParser.parseString(res.body()).asJsonObject
        applyTokens(
            token = o.get("id_token")?.asString ?: return false,
            newUid = o.get("user_id")?.asString ?: return false,
            newRefresh = o.get("refresh_token")?.asString ?: token,
            expiresInSec = o.get("expires_in")?.asString?.toLongOrNull() ?: 3600,
        )
        true
    }.getOrDefault(false)

    private fun applyTokens(token: String, newUid: String, newRefresh: String, expiresInSec: Long) {
        idToken = token
        uid = newUid
        tokenExpiresAt = System.currentTimeMillis() + expiresInSec * 1000
        refreshToken = newRefresh
        onAuthChanged(newRefresh, newUid)
    }

    private fun HttpRequest.Builder.auth(): HttpRequest.Builder {
        currentToken()?.let { header("Authorization", "Bearer $it") }
        return this
    }

    // ── HTTP ──────────────────────────────────────────────
    private fun get(url: String): JsonObject? = runCatching {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).auth().GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (res.statusCode() in 200..299) JsonParser.parseString(res.body()).asJsonObject else null
    }.getOrNull()

    private fun post(url: String, bodyJson: String): JsonObject? = runCatching {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .auth()
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (res.statusCode() in 200..299) {
            JsonParser.parseString(res.body()).asJsonObject
        } else {
            System.err.println("Firestore POST ${res.statusCode()}: ${res.body().take(300)}")
            null
        }
    }.onFailure { System.err.println("Firestore POST error: $it") }.getOrNull()

    private fun patch(url: String, bodyJson: String): Boolean = runCatching {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .auth()
                .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        res.statusCode() in 200..299
    }.getOrDefault(false)

    // ── Firestore 값 인코딩/디코딩 ────────────────────────
    private fun v(value: Any): JsonObject {
        val o = JsonObject()
        when (value) {
            is String -> o.addProperty("stringValue", value)
            is Boolean -> o.addProperty("booleanValue", value)
            is Long -> o.addProperty("integerValue", value.toString())
            is Int -> o.addProperty("integerValue", value.toString())
            else -> o.addProperty("stringValue", value.toString())
        }
        return o
    }

    /** null 값 필드는 생략한다 (Gson 기본 직렬화가 null을 누락시켜 빈 Value가 되는 것 방지) */
    private fun fields(map: Map<String, Any?>): JsonObject {
        val f = JsonObject()
        map.forEach { (key, value) -> if (value != null) f.add(key, v(value)) }
        val root = JsonObject()
        root.add("fields", f)
        return root
    }

    private fun JsonObject.str(name: String): String? =
        getAsJsonObject("fields")?.getAsJsonObject(name)?.get("stringValue")?.asString

    private fun JsonObject.long(name: String): Long? =
        getAsJsonObject("fields")?.getAsJsonObject(name)?.get("integerValue")?.asString?.toLongOrNull()

    private fun JsonObject.bool(name: String): Boolean =
        getAsJsonObject("fields")?.getAsJsonObject(name)?.get("booleanValue")?.asBoolean ?: false

    private fun JsonObject.docId(): String = get("name").asString.substringAfterLast("/")

    // ── 방 ────────────────────────────────────────────────

    /**
     * 초대 코드 → 방. inviteCodes/{code} 매핑을 먼저 조회하고,
     * 없으면(구버전 코드) rooms 컬렉션 쿼리로 폴백한다 — 규칙 배포 후에는
     * 폴백이 거부되므로 방을 다시 공유해 새 코드를 받으면 된다.
     */
    fun findRoomByCode(code: String): RoomMeta? {
        val normalized = code.trim().uppercase()
        // 사용자 입력이 URL 경로에 들어가므로 인코딩 (P3-12)
        val encoded = java.net.URLEncoder.encode(normalized, "UTF-8")
        get("$base/inviteCodes/$encoded?key=$apiKey")?.str("roomId")?.let { roomId ->
            getRoom(roomId)?.let { return it }
        }
        return legacyFindRoomByCode(normalized)
    }

    private fun legacyFindRoomByCode(code: String): RoomMeta? {
        val query = """
            {"structuredQuery":{"from":[{"collectionId":"rooms"}],
             "where":{"fieldFilter":{"field":{"fieldPath":"inviteCode"},"op":"EQUAL",
                      "value":{"stringValue":"${code.replace("\"", "")}"}}},"limit":1}}
        """.trimIndent()
        val res = runCatching {
            val r = http.send(
                HttpRequest.newBuilder(URI.create("$base:runQuery?key=$apiKey")).timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .auth()
                    .POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
            )
            if (r.statusCode() in 200..299) JsonParser.parseString(r.body()).asJsonArray else null
        }.getOrNull() ?: return null
        val doc = res.firstOrNull { it.asJsonObject.has("document") }
            ?.asJsonObject?.getAsJsonObject("document") ?: return null
        return roomMeta(doc)
    }

    /** members/{uid} 문서 보장 — 보안 규칙의 방 접근 근거 */
    fun ensureMember(remoteRoomId: String): Boolean {
        currentToken() // uid 확보
        val memberId = uid ?: return false
        return patch(
            "$base/rooms/$remoteRoomId/members/$memberId?key=$apiKey&updateMask.fieldPaths=joinedAt",
            gson.toJson(fields(mapOf("joinedAt" to System.currentTimeMillis()))),
        )
    }

    /** 초대 코드 → 방 매핑 문서 생성 (방 생성 시) */
    fun createInviteCode(code: String, remoteRoomId: String): Boolean = patch(
        "$base/inviteCodes/$code?key=$apiKey",
        gson.toJson(
            fields(mapOf("roomId" to remoteRoomId, "createdAt" to System.currentTimeMillis()))
        ),
    )

    fun getRoom(remoteId: String): RoomMeta? =
        get("$base/rooms/$remoteId?key=$apiKey")?.let { roomMeta(it) }

    private fun roomMeta(doc: JsonObject) = RoomMeta(
        remoteId = doc.docId(),
        name = doc.str("name") ?: "이름 없는 방",
        icon = doc.str("icon") ?: "🎲",
        inviteCode = doc.str("inviteCode"),
        themeColor = doc.long("themeColor") ?: 0xFF8EC5E8,
        backgroundKey = doc.str("backgroundKey") ?: "preset_lighthouse",
    )

    fun createRoom(name: String, icon: String, inviteCode: String): RoomMeta? {
        val body = fields(
            mapOf(
                "name" to name, "icon" to icon, "createdAt" to System.currentTimeMillis(),
                "inviteCode" to inviteCode,
                "themeColor" to 0xFF8EC5E8, "backgroundKey" to "preset_lighthouse",
            )
        )
        return post("$base/rooms?key=$apiKey", gson.toJson(body))?.let { roomMeta(it) }
    }

    fun updateRoomSettings(remoteId: String, themeColor: Long, backgroundKey: String): Boolean {
        val body = fields(mapOf("themeColor" to themeColor, "backgroundKey" to backgroundKey))
        return patch(
            "$base/rooms/$remoteId?key=$apiKey" +
                "&updateMask.fieldPaths=themeColor&updateMask.fieldPaths=backgroundKey",
            gson.toJson(body),
        )
    }

    // ── 메시지 ────────────────────────────────────────────

    private fun parseMessage(doc: JsonObject) = Message(
        docId = doc.docId(),
        type = doc.str("type") ?: "TEXT",
        body = doc.str("body") ?: "",
        diceExpr = doc.str("diceExpr"),
        senderName = doc.str("senderName"),
        senderEmoji = doc.str("senderEmoji"),
        senderIsGm = doc.bool("senderIsGm"),
        senderIsBot = doc.bool("senderIsBot"),
        senderNameColor = doc.long("senderNameColor"),
        senderBubbleColor = doc.long("senderBubbleColor"),
        isOoc = doc.bool("isOoc"),
        editedAt = doc.long("editedAt"),
        createdAt = doc.long("createdAt") ?: 0L,
        authorUid = doc.str("authorUid") ?: "",
        avatarId = doc.str("avatarId"),
    )

    /**
     * 전체 목록 — 방 최초 진입 시 1회만 사용.
     * @return null이면 네트워크/서버 오류 — 호출부는 커서를 전진시키면 안 된다 (P1-6)
     */
    fun listMessages(remoteRoomId: String): List<Message>? {
        val out = mutableListOf<Message>()
        var pageToken: String? = null
        do {
            val url = "$base/rooms/$remoteRoomId/messages?key=$apiKey&pageSize=300&orderBy=createdAt" +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            // 페이지 하나라도 실패하면 부분 결과로 커서를 오염시키지 않도록 에러로 처리
            val res = get(url) ?: return null
            res.getAsJsonArray("documents")?.forEach { el -> out += parseMessage(el.asJsonObject) }
            pageToken = res.get("nextPageToken")?.asString
        } while (pageToken != null)
        return out
    }

    /**
     * 증분 폴링 — createdAt >= (since - 여유 30초)인 메시지를 읽는다 (P1-4).
     * 시계 오차·동시 타임스탬프·커밋 순서 역전으로 인한 영구 누락을 여유 윈도우로 흡수하고,
     * 중복은 호출부의 docId dedup이 걸러낸다.
     * @return null이면 오류 — 호출부는 커서를 전진시키면 안 된다
     */
    fun listMessagesSince(remoteRoomId: String, since: Long): List<Message>? {
        if (since <= 0) return listMessages(remoteRoomId)
        val from = (since - 30_000).coerceAtLeast(0)
        val query = """
            {"structuredQuery":{"from":[{"collectionId":"messages"}],
             "where":{"fieldFilter":{"field":{"fieldPath":"createdAt"},"op":"GREATER_THAN_OR_EQUAL",
                      "value":{"integerValue":"$from"}}},
             "orderBy":[{"field":{"fieldPath":"createdAt"},"direction":"ASCENDING"}]}}
        """.trimIndent()
        val res = runCatching {
            val r = http.send(
                HttpRequest.newBuilder(URI.create("$base/rooms/$remoteRoomId:runQuery?key=$apiKey")).timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .auth()
                    .POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
            )
            if (r.statusCode() in 200..299) JsonParser.parseString(r.body()).asJsonArray else null
        }.getOrNull() ?: return null
        return res.mapNotNull { el ->
            el.asJsonObject.getAsJsonObject("document")?.let { parseMessage(it) }
        }
    }

    fun postMessage(remoteRoomId: String, values: Map<String, Any?>): Boolean =
        post("$base/rooms/$remoteRoomId/messages?key=$apiKey", gson.toJson(fields(values))) != null

    /** 프로필 이미지(base64 JPEG) — 모바일이 올린 avatars/{hash} 문서 */
    fun fetchAvatar(remoteRoomId: String, avatarId: String): ByteArray? {
        val doc = get("$base/rooms/$remoteRoomId/avatars/$avatarId?key=$apiKey") ?: return null
        val data = doc.str("data") ?: return null
        return runCatching { java.util.Base64.getDecoder().decode(data) }.getOrNull()
    }
}
