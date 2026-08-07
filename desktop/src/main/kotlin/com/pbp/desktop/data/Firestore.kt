package com.pbp.desktop.data

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import com.pbp.shared.Protocol

/** 상대가 올린 캐릭터 한 명 (J8). 값은 **이름만** 온다 — 숫자는 소유자 기기에만 있다 */
data class PeerCharacter(val name: String, val emoji: String, val stats: List<String>)

/** 메시지 (Firestore 문서 ↔ 로컬 모델) — 안드로이드 앱과 같은 스키마 */
data class Message(
    val docId: String,
    val type: String,
    val body: String,
    val diceExpr: String?,
    /** 판정 등급 (critical/extreme/hard/success/fail/fumble) */
    val diceOutcome: String?,
    val senderName: String?,
    val senderEmoji: String?,
    val senderIsGm: Boolean,
    val senderIsBot: Boolean,
    val senderNameColor: Long?,
    val senderBubbleColor: Long?,
    val senderTextColor: Long?,
    val isOoc: Boolean,
    val editedAt: Long?,
    val createdAt: Long,
    /** JUDGE 요청의 대상 캐릭터 이름 (J1) */
    val judgeTarget: String?,
    /** 이 굴림이 응답한 요청의 키 (J1) */
    val judgeRef: String?,
    /**
     * 서버에 기록된 시각 — 폴 커서 전용. 표시·정렬에는 쓰지 않는다 (V1).
     * 이 필드가 없는 옛 문서는 createdAt으로 떨어진다.
     */
    val syncAt: Long,
    val authorUid: String,
    val avatarId: String?,
)

data class RoomMeta(
    val remoteId: String,
    val name: String,
    val icon: String,
    val inviteCode: String?,
    val themeColor: Long,
    /** null = 서버에 배경이 없음(상대가 커스텀 사용 중) — 수신 측은 자기 배경 유지 */
    val backgroundKey: String?,
    val rule: String? = null,
    /** 방이 만들어진 시각 — 로그 맨 위 날짜 구분선에 쓴다. 아주 옛 방 문서에는 없다 */
    val createdAt: Long? = null,
    /** 이 시각 이전 로그는 누군가 초기화했다 (A6) */
    val logsClearedAt: Long? = null,
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

    /** 토큰 발급/갱신 직렬화용 — HTTP는 이 락 밖에서 돌지 않지만, 락 자체는 짧게 유지 (C15) */
    private val tokenLock = Any()

    /** 인증 실패 네거티브 캐시 (S1) — 이 시각까지는 재시도하지 않는다 */
    @Volatile private var authRetryBlockedUntil = 0L

    private fun currentToken(): String? {
        // 유효한 토큰이 있으면 락 없이 즉시 반환 — 폴링·전송이 서로 막지 않는다
        idToken?.let { if (System.currentTimeMillis() < tokenExpiresAt - 60_000) return it }
        // 실패 직후에는 락도 잡지 않고 즉시 무인증 진행 — 인증 서버가 느리거나
        // 익명 로그인이 꺼져 있을 때 매 요청이 identitytoolkit 왕복 뒤로 직렬화되는 것 방지 (S1)
        if (System.currentTimeMillis() < authRetryBlockedUntil) return null
        synchronized(tokenLock) {
            // 락 대기 중 다른 스레드가 갱신/실패했을 수 있다
            idToken?.let { if (System.currentTimeMillis() < tokenExpiresAt - 60_000) return it }
            if (System.currentTimeMillis() < authRetryBlockedUntil) return null
            val saved = refreshToken
            val token = if (saved != null) {
                when (refreshIdToken(saved)) {
                    RefreshResult.OK -> idToken
                    // 네트워크·서버 오류는 토큰을 버리지 않는다 — 새 익명 계정을 만들면
                    // 기존 UID(=방 멤버십)를 영구히 잃는다 (R4)
                    RefreshResult.TRANSIENT -> null
                    // 토큰이 폐기된 경우에만 재가입
                    RefreshResult.REVOKED -> if (signUpAnonymous()) idToken else null
                }
            } else {
                if (signUpAnonymous()) idToken else null
            }
            if (token == null) {
                authRetryBlockedUntil = System.currentTimeMillis() + 60_000
            }
            return token
        }
    }

    /** 401을 받은 호출부가 다음 요청에서 강제 갱신하도록 (C15) */
    private fun invalidateToken() {
        idToken = null
        tokenExpiresAt = 0
    }

    private enum class RefreshResult { OK, TRANSIENT, REVOKED }

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

    private fun refreshIdToken(token: String): RefreshResult = runCatching {
        val res = http.send(
            HttpRequest.newBuilder(URI.create("https://securetoken.googleapis.com/v1/token?key=$apiKey")).timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "grant_type=refresh_token&refresh_token=" +
                            java.net.URLEncoder.encode(token, "UTF-8"),
                        StandardCharsets.UTF_8,
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (res.statusCode() !in 200..299) {
            // 400 + invalid_grant/TOKEN_EXPIRED = 토큰 폐기(재가입 필요),
            // 그 외(네트워크·5xx·429)는 일시 오류로 보고 토큰을 유지한다 (R4)
            val body = res.body()
            val revoked = res.statusCode() == 400 &&
                listOf("INVALID_REFRESH_TOKEN", "TOKEN_EXPIRED", "USER_NOT_FOUND", "invalid_grant")
                    .any { body.contains(it, ignoreCase = true) }
            return if (revoked) RefreshResult.REVOKED else RefreshResult.TRANSIENT
        }
        val o = JsonParser.parseString(res.body()).asJsonObject
        applyTokens(
            token = o.get("id_token")?.asString ?: return RefreshResult.TRANSIENT,
            newUid = o.get("user_id")?.asString ?: return RefreshResult.TRANSIENT,
            newRefresh = o.get("refresh_token")?.asString ?: token,
            expiresInSec = o.get("expires_in")?.asString?.toLongOrNull() ?: 3600,
        )
        RefreshResult.OK
    }.getOrDefault(RefreshResult.TRANSIENT)

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

    /**
     * 401/403이면 토큰을 무효화하고 한 번만 재시도한다 (C15).
     * 403 PERMISSION_DENIED도 만료 토큰에서 나온다 — 주석은 둘 다라고 하면서
     * 실제로는 401만 처리하고 있었다 (E15).
     */
    private fun sendWithRetry(build: () -> HttpRequest): HttpResponse<String>? = runCatching {
        var res = http.send(build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (res.statusCode() in setOf(401, 403) && idToken != null) {
            invalidateToken()
            res = http.send(build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
        res
    }.getOrNull()

    private fun get(url: String): JsonObject? {
        val res = sendWithRetry {
            HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).auth().GET().build()
        } ?: return null
        return if (res.statusCode() in 200..299) {
            runCatching { JsonParser.parseString(res.body()).asJsonObject }.getOrNull()
        } else null
    }

    private fun post(url: String, bodyJson: String): JsonObject? {
        val res = sendWithRetry {
            HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .auth()
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build()
        } ?: run {
            System.err.println("Firestore POST 실패(네트워크): $url")
            return null
        }
        return if (res.statusCode() in 200..299) {
            runCatching { JsonParser.parseString(res.body()).asJsonObject }.getOrNull()
        } else {
            System.err.println("Firestore POST ${res.statusCode()}: ${res.body().take(300)}")
            null
        }
    }

    private fun deleteDoc(url: String): Boolean {
        val res = sendWithRetry {
            HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .auth()
                .DELETE()
                .build()
        } ?: return false
        if (res.statusCode() !in 200..299) {
            System.err.println("Firestore DELETE ${res.statusCode()}: ${res.body().take(300)}")
        }
        return res.statusCode() in 200..299
    }

    private fun patch(url: String, bodyJson: String): Boolean {
        val res = sendWithRetry {
            HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .auth()
                .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build()
        } ?: return false
        if (res.statusCode() !in 200..299) {
            System.err.println("Firestore PATCH ${res.statusCode()}: ${res.body().take(300)}")
        }
        return res.statusCode() in 200..299
    }

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

    /** Firestore 타임스탬프 문자열 ↔ epoch 밀리초 */
    private fun rfc3339(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).toString()

    private fun JsonObject.timestamp(name: String): Long? =
        getAsJsonObject("fields")?.getAsJsonObject(name)?.get("timestampValue")?.asString
            ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun JsonObject.long(name: String): Long? =
        getAsJsonObject("fields")?.getAsJsonObject(name)?.get("integerValue")?.asString?.toLongOrNull()

    private fun JsonObject.bool(name: String): Boolean =
        getAsJsonObject("fields")?.getAsJsonObject(name)?.get("booleanValue")?.asBoolean ?: false

    // name 필드가 없는 기형 응답에서 NPE로 폴링이 죽지 않게 (C2)
    private fun JsonObject.docId(): String =
        get("name")?.takeIf { it.isJsonPrimitive }?.asString?.substringAfterLast("/") ?: ""

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
            val r = sendWithRetry {
                HttpRequest.newBuilder(URI.create("$base:runQuery?key=$apiKey")).timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .auth()
                    .POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8))
                    .build()
            } ?: return null
            if (r.statusCode() in 200..299) JsonParser.parseString(r.body()).asJsonArray else null
        }.getOrNull() ?: return null
        val doc = res.firstOrNull { it.asJsonObject.has("document") }
            ?.asJsonObject?.getAsJsonObject("document") ?: return null
        return roomMeta(doc)
    }

    /**
     * members/{uid} 문서 보장 — 보안 규칙의 방 접근 근거.
     * platform을 함께 남긴다: 읽음 확인은 모바일끼리만 성립하므로, 데스크톱이
     * 섞여 있으면 모바일 쪽이 "읽음" 표시를 생략해야 한다.
     */
    fun ensureMember(remoteRoomId: String): Boolean {
        currentToken() // uid 확보
        val memberId = uid ?: return false
        return patch(
            "$base/rooms/$remoteRoomId/members/$memberId?key=$apiKey" +
                "&updateMask.fieldPaths=joinedAt&updateMask.fieldPaths=platform",
            gson.toJson(
                fields(
                    mapOf(
                        "joinedAt" to System.currentTimeMillis(),
                        "platform" to Protocol.Platform.DESKTOP,
                    )
                )
            ),
        )
    }

    /** 방마다 마지막으로 올린 캐릭터 명단 — 같은 내용을 다시 쓰지 않기 위한 가드 */
    private val pushedCharacters = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 이 PC의 캐릭터 명단을 멤버 문서에 올린다 (J0) — 모바일 GM의 요청 대상 목록에 뜬다.
     * 값은 **이름만** 싣는다. 명단이 그대로면 쓰지 않는다.
     */
    fun pushCharacters(remoteRoomId: String, characters: List<Pair<String, List<String>>>) {
        val signature = characters.joinToString("|") { "${it.first}:${it.second.joinToString(",")}" }
        if (pushedCharacters[remoteRoomId] == signature) return
        val memberId = uid ?: return
        val array = JsonArray()
        characters.forEach { (name, stats) ->
            val statValues = JsonArray()
            stats.forEach { stat -> statValues.add(v(stat)) }
            val entry = JsonObject()
            entry.add(Protocol.Character.NAME, v(name))
            entry.add(Protocol.Character.EMOJI, v(""))
            val statArray = JsonObject()
            statArray.add("values", statValues)
            val statsValue = JsonObject()
            statsValue.add("arrayValue", statArray)
            entry.add(Protocol.Character.STATS, statsValue)
            val fields = JsonObject()
            fields.add("fields", entry)
            val mapValue = JsonObject()
            mapValue.add("mapValue", fields)
            array.add(mapValue)
        }
        val values = JsonArray().also { it.addAll(array) }
        val arrayField = JsonObject()
        arrayField.add("values", values)
        val characterField = JsonObject()
        characterField.add("arrayValue", arrayField)
        val fields = JsonObject()
        fields.add(Protocol.Field.CHARACTERS, characterField)
        val root = JsonObject()
        root.add("fields", fields)
        val ok = patch(
            "$base/rooms/$remoteRoomId/members/$memberId?key=$apiKey" +
                "&updateMask.fieldPaths=${Protocol.Field.CHARACTERS}",
            gson.toJson(root),
        )
        if (ok) pushedCharacters[remoteRoomId] = signature
    }

    /**
     * 다른 멤버가 올린 캐릭터 명단 (J8). **GM이 판정 요청 창을 열 때만** 부른다 —
     * 폴링에 얹으면 2.5초마다 members를 읽게 되므로 절대 상시로 만들지 말 것.
     * 1:1 방이라 한 번에 문서 2건(=읽기 2회)이다.
     */
    fun listPeerCharacters(remoteRoomId: String): List<PeerCharacter> {
        val myUid = uid
        val res = get("$base/rooms/$remoteRoomId/members?key=$apiKey") ?: return emptyList()
        val documents = res.getAsJsonArray("documents") ?: return emptyList()
        val out = mutableListOf<PeerCharacter>()
        documents.forEach { element ->
            val doc = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
            if (doc.docId() == myUid) return@forEach
            val entries = doc.getAsJsonObject("fields")
                ?.getAsJsonObject(Protocol.Field.CHARACTERS)
                ?.getAsJsonObject("arrayValue")
                ?.getAsJsonArray("values") ?: return@forEach
            entries.forEach entry@{ raw ->
                // 상대가 구버전이거나 쓰다 만 문서일 수 있다 — 한 건이 깨져도 나머지는 살린다
                val fields = runCatching {
                    raw.asJsonObject.getAsJsonObject("mapValue").getAsJsonObject("fields")
                }.getOrNull() ?: return@entry
                val name = fields.getAsJsonObject(Protocol.Character.NAME)
                    ?.get("stringValue")?.asString?.takeIf { it.isNotBlank() } ?: return@entry
                val stats = fields.getAsJsonObject(Protocol.Character.STATS)
                    ?.getAsJsonObject("arrayValue")?.getAsJsonArray("values")
                    ?.mapNotNull { runCatching { it.asJsonObject.get("stringValue").asString }.getOrNull() }
                    .orEmpty()
                out += PeerCharacter(
                    name = name,
                    emoji = fields.getAsJsonObject(Protocol.Character.EMOJI)
                        ?.get("stringValue")?.asString.orEmpty(),
                    stats = stats,
                )
            }
        }
        return out
    }

    /** 방마다 마지막으로 올린 입력 중 시각 — 스로틀 기준 */
    private val lastTypingPushAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 입력 중 알림 — **쓰기만 한다.** 데스크톱은 상대의 입력 중을 표시하지 않으므로
     * 읽기가 늘지 않는다(모바일 쪽은 이미 붙어 있는 members 리스너로 받는다).
     * 실제로 글자가 바뀔 때만 부르고, 손을 멈추면 typingUntil이 지나 저절로 꺼진다.
     */
    fun pushTyping(remoteRoomId: String, name: String) {
        val now = System.currentTimeMillis()
        if (now - (lastTypingPushAt[remoteRoomId] ?: 0L) < Protocol.TYPING_THROTTLE_MS) return
        lastTypingPushAt[remoteRoomId] = now
        val memberId = uid ?: return
        patch(
            "$base/rooms/$remoteRoomId/members/$memberId?key=$apiKey" +
                "&updateMask.fieldPaths=typingUntil&updateMask.fieldPaths=typingName" +
                "&updateMask.fieldPaths=platform",
            gson.toJson(
                fields(
                    mapOf(
                        "typingUntil" to now + Protocol.TYPING_TTL_MS,
                        "typingName" to name,
                        "platform" to Protocol.Platform.DESKTOP,
                    )
                )
            ),
        )
    }

    /** 전송·비움 때 즉시 끈다. 올린 적이 없으면 쓰지 않는다 */
    fun clearTyping(remoteRoomId: String) {
        if (lastTypingPushAt.remove(remoteRoomId) == null) return
        val memberId = uid ?: return
        patch(
            "$base/rooms/$remoteRoomId/members/$memberId?key=$apiKey&updateMask.fieldPaths=typingUntil",
            gson.toJson(fields(mapOf("typingUntil" to 0L))),
        )
    }

    /** 초대 코드 → 방 매핑 문서 생성 (방 생성 시) */
    fun createInviteCode(code: String, remoteRoomId: String): Boolean = patch(
        "$base/inviteCodes/$code?key=$apiKey",
        gson.toJson(
            fields(mapOf("roomId" to remoteRoomId, "createdAt" to System.currentTimeMillis()))
        ),
    )

    fun getRoom(remoteId: String): RoomMeta? = runCatching {
        getRoomInternal(remoteId)
    }.getOrNull()

    private fun getRoomInternal(remoteId: String): RoomMeta? =
        get("$base/rooms/$remoteId?key=$apiKey")?.let { roomMeta(it) }

    private fun roomMeta(doc: JsonObject) = RoomMeta(
        remoteId = doc.docId(),
        name = doc.str("name") ?: "이름 없는 방",
        icon = "", // 방 아이콘 폐지 — 배경으로만 구분 (모바일과 동일)
        inviteCode = doc.str("inviteCode"),
        themeColor = doc.long("themeColor") ?: Protocol.DEFAULT_THEME_COLOR,
        backgroundKey = doc.str("backgroundKey"),
        rule = doc.str("rule"),
        createdAt = doc.long("createdAt"),
        logsClearedAt = doc.long(Protocol.Field.LOGS_CLEARED_AT),
    )

    /**
     * "이 시각 이전 로그를 비웠다"를 방 문서에 남긴다 (A6) — 폴링만 쓰는 상대가
     * 문서 삭제를 볼 수 없어서 필요한 표식. 이미 받아 오는 방 메타에 얹으므로 읽기가 늘지 않는다.
     */
    fun setLogsClearedAt(remoteRoomId: String, millis: Long): Boolean = patch(
        "$base/rooms/$remoteRoomId?key=$apiKey" +
            "&updateMask.fieldPaths=${Protocol.Field.LOGS_CLEARED_AT}",
        gson.toJson(fields(mapOf(Protocol.Field.LOGS_CLEARED_AT to millis))),
    )

    fun createRoom(name: String, inviteCode: String, rule: String): RoomMeta? {
        val body = fields(
            mapOf(
                "name" to name, "icon" to "", "createdAt" to System.currentTimeMillis(),
                "inviteCode" to inviteCode, "rule" to rule,
                "themeColor" to Protocol.DEFAULT_THEME_COLOR,
            )
        )
        return post("$base/rooms?key=$apiKey", gson.toJson(body))?.let { roomMeta(it) }
    }

    /** 테마 컬러만 전파한다 — 배경은 기기마다 따로 고르는 개인 설정 (모바일과 동일) */
    fun updateRoomSettings(remoteId: String, themeColor: Long): Boolean = patch(
        "$base/rooms/$remoteId?key=$apiKey&updateMask.fieldPaths=themeColor",
        gson.toJson(fields(mapOf("themeColor" to themeColor))),
    )

    // ── 메시지 ────────────────────────────────────────────

    private fun parseMessage(doc: JsonObject) = Message(
        docId = doc.docId(),
        type = doc.str("type") ?: "TEXT",
        body = doc.str("body") ?: "",
        diceExpr = doc.str("diceExpr"),
        diceOutcome = doc.str("diceOutcome"),
        senderName = doc.str("senderName"),
        senderEmoji = doc.str("senderEmoji"),
        senderIsGm = doc.bool("senderIsGm"),
        senderIsBot = doc.bool("senderIsBot"),
        senderNameColor = doc.long("senderNameColor"),
        senderBubbleColor = doc.long("senderBubbleColor"),
        senderTextColor = doc.long("senderTextColor"),
        isOoc = doc.bool("isOoc"),
        editedAt = doc.long("editedAt"),
        createdAt = doc.long("createdAt") ?: 0L,
        judgeTarget = doc.str("judgeTarget"),
        judgeRef = doc.str("judgeRef"),
        // syncAt이 없는 옛 문서는 createdAt으로 — 커서가 뒤로 가지 않게만 하면 된다
        syncAt = doc.timestamp("syncAt") ?: doc.long("createdAt") ?: 0L,
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
            // 토큰에 +·= 가 들어가므로 인코딩하지 않으면 2페이지 이후가 영구 실패 (C12)
            val url = "$base/rooms/$remoteRoomId/messages?key=$apiKey&pageSize=300&orderBy=createdAt" +
                (pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            // 페이지 하나라도 실패하면 부분 결과로 커서를 오염시키지 않도록 에러로 처리.
            // 파싱 예외도 오류로 — 기형 응답 1건이 폴링을 영구 정지시키지 않게 (C2)
            val res = get(url) ?: return null
            runCatching {
                res.getAsJsonArray("documents")?.forEach { el ->
                    // docId가 비면(기형 name) LazyColumn 키 충돌·dedup 오염 — 버린다
                    parseMessage(el.asJsonObject).takeIf { it.docId.isNotEmpty() }?.let { out += it }
                }
                pageToken = res.get("nextPageToken")?.takeIf { it.isJsonPrimitive }?.asString
            }.getOrElse { return null }
        } while (pageToken != null)
        return out
    }

    /**
     * 증분 폴링 — **syncAt(서버 기록 시각)** 기준으로 읽는다 (V1).
     *
     * 예전에는 createdAt(작성 기기의 시계)으로 잘랐는데, 오프라인에서 쓴 메시지가 나중에
     * 커밋되면 커서보다 과거 시각으로 도착해 영영 조회되지 않았다. syncAt은 커밋 시점에
     * 정해지므로 그런 역전이 없다. 표시 순서는 여전히 createdAt이다.
     *
     * 여유 윈도는 그대로 둔다 — 같은 시각 동시 커밋과 시계 오차를 흡수하고,
     * 중복은 호출부의 docId dedup이 거른다.
     * @return null이면 오류 — 호출부는 커서를 전진시키면 안 된다
     */
    fun listMessagesSince(
        remoteRoomId: String,
        since: Long,
        windowMs: Long = 30_000,
        /**
         * true면 옛 방식(createdAt)으로 조회한다. **syncAt이 없는 문서**는 타임스탬프
         * 질의에 아예 걸리지 않으므로 — 상대가 아직 구버전이면 그 메시지가 통째로
         * 안 보인다 — 가끔 이 경로로 훑어 메꾼다 (V1 전환 안전망).
         */
        byCreatedAt: Boolean = false,
    ): List<Message>? {
        if (since <= 0) return listMessages(remoteRoomId)
        // 윈도는 폴 주기×2로 동적 (P5) — 고정 30초는 활성 채팅에서 건당 ~12배 재과금
        val from = (since - windowMs).coerceAtLeast(0)
        val query = if (byCreatedAt) """
            {"structuredQuery":{"from":[{"collectionId":"messages"}],
             "where":{"fieldFilter":{"field":{"fieldPath":"createdAt"},"op":"GREATER_THAN_OR_EQUAL",
                      "value":{"integerValue":"$from"}}},
             "orderBy":[{"field":{"fieldPath":"createdAt"},"direction":"ASCENDING"}]}}
        """.trimIndent() else """
            {"structuredQuery":{"from":[{"collectionId":"messages"}],
             "where":{"fieldFilter":{"field":{"fieldPath":"syncAt"},"op":"GREATER_THAN_OR_EQUAL",
                      "value":{"timestampValue":"${rfc3339(from)}"}}},
             "orderBy":[{"field":{"fieldPath":"syncAt"},"direction":"ASCENDING"}]}}
        """.trimIndent()
        // 가장 잦은 요청이 정작 토큰 재시도 밖에 있었다 (H1) — 서버가 로컬 만료 전에
        // 토큰을 거부하면 죽은 토큰으로 매 폴이 실패해 커서가 멈추고, 수신만 하는 쪽은
        // 자연 만료(최대 1시간)까지 무증상으로 조용해진다
        val res = runCatching {
            val r = sendWithRetry {
                HttpRequest.newBuilder(URI.create("$base/rooms/$remoteRoomId:runQuery?key=$apiKey")).timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .auth()
                    .POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8))
                    .build()
            } ?: return null
            if (r.statusCode() in 200..299) JsonParser.parseString(r.body()).asJsonArray else null
        }.getOrNull() ?: return null
        // 파싱 예외도 오류(null)로 — 커서 미전진 계약(P1-6)이 흡수한다 (C2)
        return runCatching {
            res.mapNotNull { el ->
                el.asJsonObject.getAsJsonObject("document")?.let { parseMessage(it) }
                    ?.takeIf { it.docId.isNotEmpty() } // 기형 문서 방어 — 위 listMessages와 동일
            }
        }.getOrNull()
    }

    /**
     * 메시지 쓰기 — **syncAt은 서버가 찍는다** (G2).
     *
     * 예전에는 `System.currentTimeMillis()`를 타임스탬프로 인코딩했다. 모바일은 진짜
     * 서버 시각(serverTimestamp)을 쓰는데 데스크톱만 로컬 시계였고, 폴 커서는 받아 온
     * syncAt의 최대값으로 전진한다 — PC 시계가 5초만 빨라도 커서가 서버 시각을 앞질러
     * 그 사이 도착한 상대 메시지가 증분 질의에서 통째로 빠졌다. 시계가 10분 넘게
     * 어긋나면 레거시 스윕도 놓쳐 영구 유실이었다.
     *
     * commit 엔드포인트의 `updateTransforms`로 넘긴다: 쓰기 1회, 과금 동일.
     * 문서 ID는 우리가 만든다 — commit은 자동 ID를 지원하지 않는다.
     */
    fun postMessage(remoteRoomId: String, values: Map<String, Any?>): Boolean =
        commitWithServerSyncAt(
            path = "rooms/$remoteRoomId/messages/${newDocId()}",
            values = values - Protocol.Field.SYNC_AT,
            updateMask = null,
        )

    /** Firestore 자동 ID와 같은 규격 — 20자 URL-safe */
    private fun newDocId(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..20).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
    }

    private val secureRandom = java.security.SecureRandom()

    /**
     * `documents:commit`으로 문서를 쓰고 syncAt만 서버 시각으로 세운다.
     * @param updateMask null이면 문서 전체 쓰기(생성), 아니면 그 필드들만 갱신
     */
    private fun commitWithServerSyncAt(
        path: String,
        values: Map<String, Any?>,
        updateMask: List<String>?,
    ): Boolean {
        val document = fields(values).apply {
            addProperty("name", "$documentRoot/$path")
        }
        val write = JsonObject().apply {
            add("update", document)
            add(
                "updateTransforms",
                com.google.gson.JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("fieldPath", Protocol.Field.SYNC_AT)
                            addProperty("setToServerValue", "REQUEST_TIME")
                        }
                    )
                },
            )
            if (updateMask != null) {
                add(
                    "updateMask",
                    JsonObject().apply {
                        add(
                            "fieldPaths",
                            com.google.gson.JsonArray().apply { updateMask.forEach { add(it) } },
                        )
                    },
                )
            }
        }
        val body = JsonObject().apply {
            add("writes", com.google.gson.JsonArray().apply { add(write) })
        }
        return post("$base:commit?key=$apiKey", gson.toJson(body)) != null
    }

    /** commit이 요구하는 문서 절대 이름의 앞부분 */
    private val documentRoot =
        "projects/$projectId/databases/(default)/documents"

    /**
     * 메시지 편집 전파 — 모바일 pushEdit과 동일 필드(body, editedAt).
     * 커서를 함께 밀어야 상대 데스크톱의 증분 질의에 다시 걸린다 (B3). 그 커서 값도
     * 서버가 찍는다 (G2) — 로컬 시계로 밀면 편집 한 번이 커서를 앞질러 버린다.
     */
    fun updateMessage(remoteRoomId: String, docId: String, body: String, editedAt: Long): Boolean =
        commitWithServerSyncAt(
            path = "rooms/$remoteRoomId/messages/$docId",
            values = mapOf("body" to body, "editedAt" to editedAt),
            // syncAt은 마스크에 넣지 않는다 — 마스크에 있는데 fields에 없으면 "삭제"로
            // 읽힌다. 변환(updateTransforms)은 마스크와 무관하게 적용되므로 이걸로 충분하다
            updateMask = listOf("body", "editedAt"),
        )

    /** 메시지 삭제 전파 — 모바일 pushDelete와 동일 */
    fun deleteMessage(remoteRoomId: String, docId: String): Boolean =
        deleteDoc("$base/rooms/$remoteRoomId/messages/$docId?key=$apiKey")

    /**
     * 방 나가기 — 내 멤버 문서 정리 (유령 푸시 방지, 모바일 leaveRoom과 동일).
     * 레거시(deviceId) 문서를 먼저 지운다 — 내 문서를 먼저 지우면 멤버가 아니게 되어
     * 이어지는 삭제가 규칙에 거부된다 (R5)
     */
    fun leaveRoom(remoteRoomId: String, deviceId: String) {
        val myUid = uid ?: deviceId
        if (myUid != deviceId) {
            deleteDoc("$base/rooms/$remoteRoomId/members/$deviceId?key=$apiKey")
        }
        deleteDoc("$base/rooms/$remoteRoomId/members/$myUid?key=$apiKey")
    }

    /** 프로필 이미지 업로드 — 모바일 ensureAvatarUploaded와 같은 문서 형태 (data=base64) */
    fun uploadAvatar(remoteRoomId: String, avatarId: String, base64: String): Boolean =
        patch(
            "$base/rooms/$remoteRoomId/avatars/$avatarId?key=$apiKey",
            gson.toJson(fields(mapOf("data" to base64))),
        )

    /** 프로필 이미지(base64 JPEG) — 모바일이 올린 avatars/{hash} 문서 */
    fun fetchAvatar(remoteRoomId: String, avatarId: String): ByteArray? {
        val doc = get("$base/rooms/$remoteRoomId/avatars/$avatarId?key=$apiKey") ?: return null
        val data = doc.str("data") ?: return null
        return runCatching { java.util.Base64.getDecoder().decode(data) }.getOrNull()
    }
}
