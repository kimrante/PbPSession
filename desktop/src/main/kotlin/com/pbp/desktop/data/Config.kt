package com.pbp.desktop.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

/** 로컬 캐릭터 프로필 — 모바일과 같은 개념 (이름/이모지/이름색/말풍선색) */
data class Profile(
    val name: String,
    val emoji: String = "🙂",
    val nameColor: Long = 0xFFFFC46B,
    val bubbleColor: Long = 0xFFFFD9A8,
    val isGm: Boolean = false,
    /** 캐릭터 값(모바일과 동일 개념) — {값이름} 치환·판정 팔레트에 쓰인다 */
    val stats: Map<String, String>? = null,
    /** 프로필 이미지 로컬 경로 — 전송 시 축소본이 방 avatars 문서로 업로드된다 */
    val imagePath: String? = null,
)

data class JoinedRoom(
    val remoteId: String,
    var name: String,
    var icon: String,
    var inviteCode: String?,
    var themeColor: Long,
    var backgroundKey: String,
    val isMaster: Boolean,
    var activeProfileIndex: Int = 0,
    /** 방의 TRPG 룰 — 판정 매크로·판정 등급 기준. 구 config에는 없어 null 허용 */
    val rule: String? = null,
)

/** ~/.pbp-desktop/config.json — 기기 ID, 프로필, 참여한 방 목록 */
class AppConfig private constructor(
    val deviceId: String,
    val profiles: MutableList<Profile>,
    val rooms: MutableList<JoinedRoom>,
    /** 익명 인증 리프레시 토큰 — 재시작해도 같은 auth UID 유지 (P0-1) */
    @Volatile var authRefreshToken: String? = null,
    /** 앱 전체 글꼴 — "system" / "gowun" / "pretendard" (모바일 AppFonts와 동일 값) */
    @Volatile var appFont: String = "system",
    /** 오너 프로필 — 잡담·참여 인사에 쓰이는 플레이어 본인 (모바일 OwnerProfile과 동일 개념) */
    @Volatile var ownerName: String = "",
    @Volatile var ownerColor: Long = 0xFFFFD9A8,
    @Volatile var ownerImagePath: String? = null,
) {
    companion object {
        private val file = File(System.getProperty("user.home"), ".pbp-desktop/config.json")
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun load(): AppConfig {
            val loaded = runCatching {
                gson.fromJson(file.readText(Charsets.UTF_8), Saved::class.java)
            }.getOrNull()
            // 파싱 실패한 기존 파일은 덮어쓰기 전에 백업 — 무통보 초기화 방지 (P2-8)
            if (loaded == null && file.exists()) {
                runCatching {
                    val backup = File(file.parentFile, "config.json.bak")
                    file.copyTo(backup, overwrite = true)
                    System.err.println(
                        "config.json을 읽지 못해 기본값으로 시작합니다. 원본은 ${backup.absolutePath}에 보관됨"
                    )
                }
            }
            return AppConfig(
                deviceId = loaded?.deviceId ?: "desktop-${UUID.randomUUID()}",
                profiles = (loaded?.profiles ?: defaultProfiles()).toMutableList(),
                rooms = (loaded?.rooms ?: emptyList()).toMutableList(),
                authRefreshToken = loaded?.authRefreshToken,
                appFont = loaded?.appFont ?: "system",
                ownerName = loaded?.ownerName ?: "",
                ownerColor = loaded?.ownerColor ?: 0xFFFFD9A8,
                ownerImagePath = loaded?.ownerImagePath,
            ).also { it.save() }
        }

        private fun defaultProfiles() = listOf(
            Profile(name = "GM", emoji = "敍", nameColor = 0xFFFFD972, bubbleColor = 0xFFE7E2D4, isGm = true),
            Profile(name = "플레이어", emoji = "🙂"),
        )
    }

    private data class Saved(
        val deviceId: String?,
        val profiles: List<Profile>?,
        val rooms: List<JoinedRoom>?,
        val authRefreshToken: String? = null,
        val appFont: String? = null,
        val ownerName: String? = null,
        val ownerColor: Long? = null,
        val ownerImagePath: String? = null,
    )

    /**
     * 저장할 내용을 호출 스레드(=목록을 소유한 UI 스레드)에서 문자열로 굳힌다.
     * 이렇게 하지 않으면 IO 스레드의 직렬화가 UI의 리스트 변경과 겹쳐
     * ConcurrentModificationException으로 스코프 전체가 죽을 수 있다 (N8).
     */
    @Synchronized
    fun snapshot(): String = gson.toJson(
        Saved(
            deviceId, profiles.toList(), rooms.toList(), authRefreshToken, appFont,
            ownerName, ownerColor, ownerImagePath,
        )
    )

    /**
     * 목록 교체와 스냅샷을 한 락 안에서 원자적으로 (C1) — 어느 스레드에서 불러도
     * snapshot()의 toList() 순회와 clear/addAll이 교차(CME)하지 않는다.
     */
    @Synchronized
    fun replaceAndSnapshot(newRooms: List<JoinedRoom>, newProfiles: List<Profile>): String {
        rooms.clear(); rooms.addAll(newRooms)
        profiles.clear(); profiles.addAll(newProfiles)
        return snapshot()
    }

    /** 시작 시 순회용 사본 (C1) — 라이브 리스트 순회 중 변경으로 인한 CME 방지 */
    @Synchronized
    fun roomsCopy(): List<JoinedRoom> = rooms.toList()

    /** 이미 굳힌 스냅샷을 파일에 쓴다. 실패해도 예외를 밖으로 던지지 않는다 (N8). */
    @Synchronized
    fun writeSnapshot(json: String) {
        runCatching {
            file.parentFile?.mkdirs()
            // 임시 파일에 쓴 뒤 원자적 이동 — 쓰다 죽어도 기존 config가 깨지지 않는다 (P2-8)
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            runCatching {
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                // 일부 파일시스템은 ATOMIC_MOVE 미지원 — 일반 교체로 폴백
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrThrow()
        }.onFailure { System.err.println("config.json 저장 실패: $it") }
    }

    fun save() = writeSnapshot(snapshot())
}
