package com.pbp.desktop.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

/** 로컬 캐릭터 프로필 — 모바일과 같은 개념 (이름/이모지/이름색/말풍선색) */
data class Profile(
    /**
     * 기기를 넘어 이 캐릭터를 가리키는 고유 id (모바일 CharacterProfile.characterId와
     * 같은 역할). 이름은 겹칠 수 있어 판정 대상을 가리는 기준이 될 수 없다.
     * 구 config에서 읽으면 비어 있으므로 불러올 때 채운다.
     */
    val characterId: String? = java.util.UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "🙂",
    val nameColor: Long = 0xFFFFC46B,
    val bubbleColor: Long = 0xFFFFD9A8,
    val isGm: Boolean = false,
    /**
     * 이 프로필이 속한 방(원격 id). 프로필은 방 안에서 만들어지고 그 방에서만 보인다.
     * null은 방 개념이 생기기 전에 만들어진 것 — 불러올 때 방마다 사본으로 나뉜다.
     */
    val roomId: String? = null,
    /** 캐릭터 값(모바일과 동일 개념) — {값이름} 치환·판정 팔레트에 쓰인다 */
    val stats: Map<String, String>? = null,
    /** 프로필 이미지 로컬 경로 — 전송 시 축소본이 방 avatars 문서로 업로드된다 */
    val imagePath: String? = null,
    /** 말풍선 안 글씨색(ARGB). null이면 테마 기본 잉크 */
    val textColor: Long? = null,
    /**
     * 마지막으로 고친 시각. 같은 프로필을 두 기기에서 고쳤을 때 **나중 것을 남기는**
     * 기준이다. 0이면 아직 계정에 올린 적 없는 프로필.
     */
    val updatedAt: Long = 0,
)

/**
 * 참여 중인 방. **전 필드 val** — 코드베이스가 copy()로만 갱신하는데 var면
 * "같은 인스턴스를 고쳐 Compose가 변화를 놓치는" 함정이 열린다 (리뷰 D6).
 */
data class JoinedRoom(
    val remoteId: String,
    val name: String,
    /** 미사용(구 config 호환) — 방은 배경 이미지로만 구분한다 */
    val icon: String,
    val inviteCode: String?,
    val themeColor: Long,
    val backgroundKey: String,
    val isMaster: Boolean,
    val activeProfileIndex: Int = 0,
    /** 방의 TRPG 룰 — 판정 매크로·판정 등급 기준. 구 config에는 없어 null 허용 */
    val rule: String? = null,
    /** 방이 만들어진 시각 — 로그 맨 위 날짜 구분선용. 구 config에는 없어 null 허용 */
    val createdAt: Long? = null,
)

/** ~/.pbp-desktop/config.json — 기기 ID, 프로필, 참여한 방 목록 */
class AppConfig private constructor(
    val deviceId: String,
    val profiles: MutableList<Profile>,
    val rooms: MutableList<JoinedRoom>,
    /** 익명 인증 리프레시 토큰 — 재시작해도 같은 auth UID 유지 (P0-1) */
    @Volatile var authRefreshToken: String? = null,
    /** 연결된 구글 계정 주소. 비어 있으면 아직 익명 계정이다 */
    @Volatile var accountEmail: String? = null,
    /**
     * 구글 계정으로 갈아타기 전에 쓰던 uid들. 서버에 남은 옛 메시지의 authorUid는
     * 그대로라, 이 목록이 있어야 지난 내 대화가 상대 쪽으로 넘어가지 않는다.
     */
    @Volatile var pastUids: List<String> = emptyList(),
    /** 앱 전체 글꼴 — "system" / "gowun" / "pretendard" (모바일 AppFonts와 동일 값) */
    @Volatile var appFont: String = "system",
    /** 오너 프로필 — 잡담·참여 인사에 쓰이는 플레이어 본인 (모바일 OwnerProfile과 동일 개념) */
    @Volatile var ownerName: String = "",
    @Volatile var ownerColor: Long = 0xFFFFD9A8,
    @Volatile var ownerImagePath: String? = null,
    @Volatile var ownerTextColor: Long? = null,
    /** 캡처 이미지에 방 배경을 포함할지 (모바일 CaptureSettings와 같은 설정) */
    @Volatile var captureWithBackground: Boolean = true,
    /** 캡처 이미지에서 잡담(OOC)을 뺄지 (모바일 CaptureSettings와 같은 설정) */
    @Volatile var captureExcludeOoc: Boolean = false,
    /**
     * 최근 사용한 커스텀 색 — **자리별로 따로** (name/bubble/owner/theme).
     * 각 목록은 최신순 최대 [RECENT_COLORS_MAX]개, 넘치면 가장 오래된 것부터
     * 밀려난다 — 모바일 RecentColors와 같은 규칙.
     */
    val recentColorsBySlot: MutableMap<String, MutableList<Long>> = mutableMapOf(),
) {
    fun recentColors(slot: String): List<Long> = recentColorsBySlot[slot].orEmpty()

    /**
     * 커스텀 색 기록 — 자리별 목록에 중복 없이 앞으로 끌어올리고,
     * 상한을 넘으면 가장 오래된 것부터 버린다.
     */
    @Synchronized
    fun addRecentColor(slot: String, argb: Long) {
        val list = recentColorsBySlot.getOrPut(slot) { mutableListOf() }
        list.remove(argb)
        list.add(0, argb)
        while (list.size > RECENT_COLORS_MAX) list.removeAt(list.lastIndex)
    }

    companion object {
        /** 저장 상한 — 모바일 RecentColors.MAX와 같아야 한다 */
        const val RECENT_COLORS_MAX = 5

        /** 예전 기본 GM 아바타에 박혀 있던 글자 — 보이면 지운다 */
        private const val LEGACY_GM_EMOJI = "敍"

        private val file = AppPaths.file("config.json")
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun load(): AppConfig {
            // 토큰·대화 로그가 든 폴더를 통째로 소유자 전용으로 (SV7). 시작할 때 한 번
            // 걸어 두면 이미 만들어져 있던 설치본도 이때 정리된다
            runCatching { AppPaths.root.mkdirs() }
            AppPaths.restrictToOwner(AppPaths.root)
            val loaded = runCatching {
                gson.fromJson(file.readText(Charsets.UTF_8), Saved::class.java)
            }.getOrNull()
            // 파싱 실패한 기존 파일은 덮어쓰기 전에 백업 — 무통보 초기화 방지 (P2-8)
            if (loaded == null && file.exists()) {
                runCatching {
                    val backup = File(file.parentFile, "config.json.bak")
                    file.copyTo(backup, overwrite = true)
                    AppPaths.restrictToOwner(backup) // 백업에도 리프레시 토큰이 들어 있다
                    System.err.println(
                        "config.json을 읽지 못해 기본값으로 시작합니다. 원본은 ${backup.absolutePath}에 보관됨"
                    )
                }
            }
            return AppConfig(
                deviceId = loaded?.deviceId ?: "desktop-${UUID.randomUUID()}",
                // 구 config에는 characterId가 없다(Gson이 null로 채운다) — 여기서 한 번
                // 채워 넣는다. 비워 두면 판정 대상 판별이 전부 빈 문자열로 겹친다
                profiles = (loaded?.profiles ?: defaultProfiles())
                    .map { if (it.characterId.isNullOrBlank()) it.copy(characterId = UUID.randomUUID().toString()) else it }
                    // 이미 만들어진 config에는 예전 GM 아바타 글자가 남아 있다 — 기본값을
                    // 바꾸는 것만으로는 쓰던 사람 화면이 그대로라 여기서 함께 비운다
                    .map { if (it.isGm && it.emoji == LEGACY_GM_EMOJI) it.copy(emoji = "") else it }
                    .let { spreadIntoRooms(it, (loaded?.rooms ?: emptyList())) }
                    .toMutableList(),
                rooms = (loaded?.rooms ?: emptyList()).toMutableList(),
                authRefreshToken = loaded?.authRefreshToken,
                accountEmail = loaded?.accountEmail,
                pastUids = loaded?.pastUids.orEmpty(),
                appFont = loaded?.appFont ?: "system",
                ownerName = loaded?.ownerName ?: "",
                ownerColor = loaded?.ownerColor ?: 0xFFFFD9A8,
                ownerImagePath = loaded?.ownerImagePath,
                ownerTextColor = loaded?.ownerTextColor,
                captureWithBackground = loaded?.captureWithBackground ?: true,
                captureExcludeOoc = loaded?.captureExcludeOoc ?: false,
                // v0.5.0의 공용 목록은 어느 자리에 쓴 색인지 알 수 없어 승계하지 않는다
                recentColorsBySlot = loaded?.recentColorsBySlot.orEmpty()
                    .mapValues { (_, v) -> v.take(RECENT_COLORS_MAX).toMutableList() }
                    .toMutableMap(),
            ).also { it.save() }
        }

        /**
         * 방 개념이 생기기 전의 프로필을 **방마다 사본으로 나눈다**.
         *
         * 쓰던 캐릭터가 어느 방에서든 그대로 보이도록 복제한다 — 한 방에만 남기면
         * 나머지 방이 갑자기 빈손이 된다. 사본은 각자 새 id를 받아 이후로는 따로 산다.
         * 방이 아직 없으면 그대로 둔다(방을 만들 때 자리를 잡는다).
         */
        private fun spreadIntoRooms(
            profiles: List<Profile>,
            rooms: List<JoinedRoom>,
        ): List<Profile> {
            val (roomless, owned) = profiles.partition { it.roomId == null }
            if (roomless.isEmpty() || rooms.isEmpty()) return profiles
            return owned + rooms.flatMap { room ->
                roomless
                    // GM은 마스터인 방에만 — 참여자 방에 GM을 두면 없던 서술 권한이 생긴다
                    .filter { !it.isGm || room.isMaster }
                    .map { profile ->
                        profile.copy(
                            characterId = UUID.randomUUID().toString(),
                            roomId = room.remoteId,
                        )
                    }
            }
        }

        private fun defaultProfiles() = listOf(
            // GM 아바타는 글자 없이 무채색 원만 — 모바일 기본 GM과 같다.
            // (emoji가 빈 문자열이면 아바타에 아무 글자도 그리지 않는다)
            Profile(name = "GM", emoji = "", nameColor = 0xFFFFD972, bubbleColor = 0xFFE7E2D4, isGm = true),
            Profile(name = "플레이어", emoji = "🙂"),
        )
    }

    private data class Saved(
        val deviceId: String?,
        val profiles: List<Profile>?,
        val rooms: List<JoinedRoom>?,
        val authRefreshToken: String? = null,
        val accountEmail: String? = null,
        val pastUids: List<String>? = null,
        val appFont: String? = null,
        val ownerName: String? = null,
        val ownerColor: Long? = null,
        val ownerImagePath: String? = null,
        val ownerTextColor: Long? = null,
        val captureWithBackground: Boolean? = null,
        val captureExcludeOoc: Boolean? = null,
        val recentColorsBySlot: Map<String, MutableList<Long>>? = null,
    )

    /**
     * 저장할 내용을 호출 스레드(=목록을 소유한 UI 스레드)에서 문자열로 굳힌다.
     * 이렇게 하지 않으면 IO 스레드의 직렬화가 UI의 리스트 변경과 겹쳐
     * ConcurrentModificationException으로 스코프 전체가 죽을 수 있다 (N8).
     */
    @Synchronized
    fun snapshot(): String = gson.toJson(
        Saved(
            deviceId, profiles.toList(), rooms.toList(), authRefreshToken, accountEmail,
            pastUids.toList(), appFont,
            ownerName, ownerColor, ownerImagePath, ownerTextColor, captureWithBackground,
            captureExcludeOoc, recentColorsBySlot.toMap(),
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

    /** 같은 이유의 프로필 사본 (E14) */
    @Synchronized
    fun profilesCopy(): List<Profile> = profiles.toList()

    /** 굳힌 스냅샷마다 붙는 세대 번호 — 뒤늦게 도착한 옛 쓰기를 가려내는 기준 (I2) */
    private val snapshotSequence = java.util.concurrent.atomic.AtomicLong()
    private var writtenGeneration = 0L

    /** [writeSnapshot]에 함께 넘길 세대 번호를 발급한다 — 굳히는 쪽에서 부른다 */
    fun nextSnapshotGeneration(): Long = snapshotSequence.incrementAndGet()

    /**
     * 이미 굳힌 스냅샷을 파일에 쓴다. 실패해도 예외를 밖으로 던지지 않는다 (N8).
     *
     * @param generation [nextSnapshotGeneration]이 준 번호. IO 워커 둘이 역순으로 락을
     *   잡으면 옛 json이 최종본으로 남는데(I2), 이 번호로 뒤처진 쓰기를 버린다.
     *   다음 저장이 스스로 고치긴 하지만 그 전에 앱을 닫으면 마지막 변경이 사라졌다.
     *   기본값은 지금 발급 — 굳히기와 쓰기가 붙어 있는 호출부(save)는 그대로 쓰면 된다.
     */
    @Synchronized
    fun writeSnapshot(json: String, generation: Long = nextSnapshotGeneration()) {
        if (generation < writtenGeneration) return
        writtenGeneration = generation
        runCatching {
            file.parentFile?.mkdirs()
            // 임시 파일에 쓴 뒤 원자적 이동 — 쓰다 죽어도 기존 config가 깨지지 않는다 (P2-8)
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            // 옮기기 전에 잠근다 — 권한은 파일을 따라가므로 config.json이 그대로 물려받는다
            AppPaths.restrictToOwner(tmp)
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
