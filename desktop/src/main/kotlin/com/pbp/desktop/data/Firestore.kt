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
) {
    private val base =
        "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"
    private val http = HttpClient.newHttpClient()
    private val gson = Gson()

    // ── HTTP ──────────────────────────────────────────────
    private fun get(url: String): JsonObject? = runCatching {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        if (res.statusCode() in 200..299) JsonParser.parseString(res.body()).asJsonObject else null
    }.getOrNull()

    private fun post(url: String, bodyJson: String): JsonObject? = runCatching {
        val res = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
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
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json; charset=utf-8")
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
    fun findRoomByCode(code: String): RoomMeta? {
        val query = """
            {"structuredQuery":{"from":[{"collectionId":"rooms"}],
             "where":{"fieldFilter":{"field":{"fieldPath":"inviteCode"},"op":"EQUAL",
                      "value":{"stringValue":"${code.uppercase()}"}}},"limit":1}}
        """.trimIndent()
        val res = runCatching {
            val r = http.send(
                HttpRequest.newBuilder(URI.create("$base:runQuery?key=$apiKey"))
                    .header("Content-Type", "application/json")
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
    fun listMessages(remoteRoomId: String): List<Message> {
        val out = mutableListOf<Message>()
        var pageToken: String? = null
        do {
            val url = "$base/rooms/$remoteRoomId/messages?key=$apiKey&pageSize=300&orderBy=createdAt" +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            val res = get(url) ?: break
            res.getAsJsonArray("documents")?.forEach { el ->
                val doc = el.asJsonObject
                out += Message(
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
            }
            pageToken = res.get("nextPageToken")?.asString
        } while (pageToken != null)
        return out
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
