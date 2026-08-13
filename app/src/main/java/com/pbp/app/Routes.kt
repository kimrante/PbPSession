package com.pbp.app

/**
 * 내비게이션 라우트 — 문자열을 7개 호출부에서 손으로 조립하던 것을 한 곳에 (리뷰 D7).
 * 오타가 런타임 크래시가 되지 않도록 빌더를 통해서만 만든다.
 */
object Routes {
    const val ROOMS = "rooms"
    const val OWNER = "owner"

    /** 캡처 미리보기 — 결과 비트맵은 CaptureHolder가 들고 있어 인자가 없다 */
    const val CAPTURE = "capture"

    const val CHAT_PATTERN = "chat/{roomId}"
    const val PROFILE_PATTERN = "profile/{profileId}/{roomId}"
    const val SETTINGS_PATTERN = "settings/{roomId}"

    const val ARG_ROOM_ID = "roomId"
    const val ARG_PROFILE_ID = "profileId"

    fun chat(roomId: Long) = "chat/$roomId"

    /**
     * profileId 0 = 새 캐릭터. 프로필은 방에 속하므로 **어느 방에서 만드는지**가 필요하다
     * (기존 프로필을 고칠 때는 그 프로필이 이미 방을 알고 있어 쓰이지 않는다).
     */
    fun profile(profileId: Long, roomId: Long = 0L) = "profile/$profileId/$roomId"

    fun settings(roomId: Long) = "settings/$roomId"
}
