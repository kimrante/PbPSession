package com.pbp.desktop.data


/**
 * 쓰이지 않는 로컬 이미지 파일 정리 (리뷰 L3) — 모바일 `ImageGc`와 같은 규칙.
 *
 * 프로필 사진·오너 사진 교체, 방 삭제로 남은 배경이 고아가 된다. 지점마다 삭제를
 * 흩어 두면 새 경로가 생길 때 빠뜨리므로 **"아무도 가리키지 않는 파일을 지운다"**
 * 한 가지 규칙으로 모았다. 시작할 때 한 번만 돌린다.
 *
 * `avatars-remote/`는 건드리지 않는다 — 그건 서버에서 다시 받을 수 있는 캐시이고,
 * 지우면 재수신에 서버 읽기가 든다.
 */
object ImageGc {

    private val DIRS = listOf(AppPaths.BACKGROUNDS, AppPaths.AVATARS_LOCAL, AppPaths.OWNER)

    /** 최근 이만큼 안에 쓰인 파일은 손대지 않는다 — 모바일과 같은 규칙 (A3) */
    private const val GRACE_MS = 24 * 60 * 60 * 1000L

    /** @return 지운 파일 수 */
    fun sweep(config: AppConfig): Int = runCatching {
        // 라이브 리스트를 그대로 순회하면 저장 스레드의 변경과 겹친다 (E14)
        val referenced = buildSet {
            config.profilesCopy().forEach { profile -> profile.imagePath?.let { add(it) } }
            config.ownerImagePath?.let { add(it) }
            config.roomsCopy().forEach { add(it.backgroundKey) }
        }
        var removed = 0
        val cutoff = System.currentTimeMillis() - GRACE_MS
        DIRS.forEach { name ->
            val dir = AppPaths.dir(name)
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.absolutePath !in referenced &&
                    file.lastModified() < cutoff
                ) {
                    if (file.delete()) removed++
                }
            }
        }
        removed
    }.getOrDefault(0)
}
