package com.pbp.app.data

import android.content.Context
import java.io.File

/**
 * 쓰이지 않는 로컬 이미지 파일 정리 (리뷰 L3).
 *
 * 고아가 생기는 길이 여럿이다 — 프로필 사진 교체, 크롭해 놓고 저장하지 않은 편집,
 * 오너 사진 재선택, 방을 지운 뒤 남은 배경, 메시지가 사라진 뒤 남은 원격 아바타.
 * 지점마다 삭제 코드를 흩어 두면 새 경로가 생길 때마다 빠뜨리므로,
 * **"아무도 가리키지 않는 파일을 지운다"** 한 가지 규칙으로 모았다.
 *
 * 앱이 시작할 때 한 번만 돌린다. 편집 중인 파일이 있을 수 없는 시점이라 안전하다.
 */
object ImageGc {

    /** 정리 대상 디렉터리 — 앱이 직접 만든 이미지만 들어 있는 곳 */
    private val DIRS = listOf("avatars", "owner", "backgrounds")

    /**
     * @return 지운 파일 수 (로그·테스트용)
     */
    suspend fun sweep(context: Context, db: AppDatabase): Int = runCatching {
        val referenced = buildSet {
            db.profileDao().allImagePaths().forEach { add(it) }
            db.messageDao().allImagePaths().forEach { add(it) }
            db.roomDao().allBackgroundKeys().forEach { add(it) }
            // OwnerProfile.load는 MainActivity에서 일어난다 — 여기(Application.onCreate)
            // 에서는 아직 비어 있으므로 저장소를 직접 읽는다. 안 그러면 오너 사진을 지운다.
            context.getSharedPreferences("pbp-settings", Context.MODE_PRIVATE)
                .getString("ownerImagePath", null)
                ?.let { add(it) }
        }
        var removed = 0
        DIRS.forEach { name ->
            val dir = File(context.filesDir, name)
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { file ->
                // 디렉터리·심볼릭 링크는 건드리지 않는다 — 우리가 만든 평범한 파일만
                if (file.isFile && file.absolutePath !in referenced) {
                    if (file.delete()) removed++
                }
            }
        }
        removed
    }.getOrDefault(0)
}
