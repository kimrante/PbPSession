package com.pbp.desktop.data

import java.io.File

/**
 * 앱 데이터 경로 — 폴더명이 5곳에 독립 파생돼 있어 하나만 바꿔도 조용히 어긋나던 것을
 * 한 곳으로 (리뷰 D4). 배경 정리(고아 파일 삭제)가 생성 경로와 반드시 같아야 한다.
 */
object AppPaths {
    private const val ROOT = ".pbp-desktop"

    val root: File get() = File(System.getProperty("user.home"), ROOT)

    fun dir(sub: String): File = File(root, sub)

    fun file(name: String): File = File(root, name)

    /** 하위 디렉터리 이름 — 문자열 재사용 방지 */
    const val BACKGROUNDS = "backgrounds"
    const val AVATARS_LOCAL = "avatars-local"
    const val AVATARS_REMOTE = "avatars-remote"
    const val OWNER = "owner"
    const val CACHE = "cache"

    /**
     * 소유자만 읽고 쓰게 잠근다 (SV7).
     *
     * config.json에는 재시작해도 같은 계정을 되찾는 **리프레시 토큰이 평문**으로 들어
     * 있고, cache 폴더에는 대화 로그가 그대로 남는다. 여러 사람이 쓰는 PC나 백업·
     * 동기화 폴더(OneDrive 등)로 새어 나가면 방 접근이 통째로 넘어간다.
     *
     * 권한을 못 걸어도 하던 일은 계속한다 — 여기서 막으면 설정 저장 자체가 멈춘다.
     */
    fun restrictToOwner(target: File) {
        runCatching {
            val path = target.toPath()
            val posix = java.nio.file.Files.getFileAttributeView(
                path, java.nio.file.attribute.PosixFileAttributeView::class.java,
            )
            if (posix != null) {
                posix.setPermissions(
                    java.nio.file.attribute.PosixFilePermissions.fromString(
                        if (target.isDirectory) "rwx------" else "rw-------"
                    )
                )
                return
            }
            // Windows — 상속받은 항목을 버리고 소유자 한 명만 남긴다
            val acl = java.nio.file.Files.getFileAttributeView(
                path, java.nio.file.attribute.AclFileAttributeView::class.java,
            ) ?: return
            val builder = java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(acl.owner)
                .setPermissions(
                    java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission::class.java)
                )
            if (target.isDirectory) {
                // 폴더 안에 새로 만들어지는 파일도 같은 권한을 물려받도록
                builder.setFlags(
                    java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT,
                    java.nio.file.attribute.AclEntryFlag.FILE_INHERIT,
                )
            }
            acl.acl = listOf(builder.build())
        }.onFailure { System.err.println("${target.name} 권한 제한 실패: $it") }
    }
}
