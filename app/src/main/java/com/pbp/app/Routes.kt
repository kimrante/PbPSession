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
    const val PROFILE_PATTERN = "profile/{profileId}"
    const val SETTINGS_PATTERN = "settings/{roomId}"

    const val ARG_ROOM_ID = "roomId"
    const val ARG_PROFILE_ID = "profileId"

    fun chat(roomId: Long) = "chat/$roomId"

    /** profileId 0 = 새 캐릭터 */
    fun profile(profileId: Long) = "profile/$profileId"

    fun settings(roomId: Long) = "settings/$roomId"
}
