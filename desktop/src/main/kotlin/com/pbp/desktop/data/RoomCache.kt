package com.pbp.desktop.data

import com.google.gson.Gson
import java.io.File

/**
 * 방별 메시지 파일 캐시 (P3 근본 수정) — 재시작 후에도 마지막 커서에서 증분으로
 * 재개해 전체 히스토리 재다운로드를 없앤다. ~/.pbp-desktop/cache/room-<id>.json.
 *
 * 한계(수용): 캐시된 과거 메시지의 편집·삭제는 재수신 윈도 밖이라 반영되지 않는다 —
 * 기존 S7(30초 밖 편집 미반영)과 같은 성격. 로그 초기화·방 나가기 시 캐시를 지운다.
 */
object RoomCacheStore {
    private val gson = Gson()
    private val dir = AppPaths.dir(AppPaths.CACHE)

    private data class Saved(val lastCreatedAt: Long, val messages: List<Message>?)

    private fun fileFor(remoteId: String) = File(dir, "room-$remoteId.json")

    fun load(remoteId: String): Pair<List<Message>, Long>? = runCatching {
        val file = fileFor(remoteId)
        if (!file.exists()) return null
        val saved = gson.fromJson(file.readText(Charsets.UTF_8), Saved::class.java) ?: return null
        // Gson은 생성자를 우회하므로 깨진 파일이면 non-null 필드에 null이 들어올 수 있다 — 방어
        val messages = saved.messages.orEmpty().filter {
            @Suppress("SENSELESS_COMPARISON")
            it.docId != null && it.docId.isNotEmpty() && it.authorUid != null && it.body != null
        }
        messages to saved.lastCreatedAt
    }.getOrNull()

    fun save(remoteId: String, messages: List<Message>, lastCreatedAt: Long) {
        runCatching {
            dir.mkdirs()
            // 임시 파일 + 원자적 교체 — 쓰다 중단돼도 기존 캐시가 깨지지 않는다
            val tmp = File(dir, "room-$remoteId.tmp")
            tmp.writeText(gson.toJson(Saved(lastCreatedAt, messages)), Charsets.UTF_8)
            runCatching {
                java.nio.file.Files.move(
                    tmp.toPath(), fileFor(remoteId).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                java.nio.file.Files.move(
                    tmp.toPath(), fileFor(remoteId).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrThrow()
        }.onFailure { System.err.println("방 캐시 저장 실패($remoteId): $it") }
    }

    fun delete(remoteId: String) {
        runCatching { fileFor(remoteId).delete() }
    }
}
