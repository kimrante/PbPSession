package com.pbp.desktop.data

import java.io.File

/**
 * 앱 데이터 경로 — 폴더명이 5곳에 독립 파생돼 있어 하나만 바꿔도 조용히 어긋나던 것을
 * 한 곳으로 (리뷰 D4). 배경 정리(고아 파일 삭제)가 생성 경로와 반드시 같아야 한다.
 */
object AppPaths {
    private const val ROOT = ".pbp-desktop"

    fun dir(sub: String): File = File(File(System.getProperty("user.home"), ROOT), sub)

    fun file(name: String): File = File(File(System.getProperty("user.home"), ROOT), name)

    /** 하위 디렉터리 이름 — 문자열 재사용 방지 */
    const val BACKGROUNDS = "backgrounds"
    const val AVATARS_LOCAL = "avatars-local"
    const val AVATARS_REMOTE = "avatars-remote"
    const val OWNER = "owner"
    const val CACHE = "cache"
}
