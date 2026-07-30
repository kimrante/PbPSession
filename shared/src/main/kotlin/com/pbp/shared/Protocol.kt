package com.pbp.shared

/**
 * Firestore 와이어 프로토콜 — 컬렉션·필드명과 값 상수.
 *
 * **3곳이 항상 일치해야 한다**: 이 파일 / `functions/index.js` / `firestore.rules`.
 * JS와 규칙 파일은 이 상수를 소비할 수 없으므로, 스키마를 바꿀 때는
 * `docs/architecture.md`의 스키마 표와 함께 세 곳을 같이 고칠 것 (리뷰 A2).
 */
object Protocol {

    /** 컬렉션 경로 조각 */
    object Collection {
        const val ROOMS = "rooms"
        const val MESSAGES = "messages"
        const val MEMBERS = "members"
        const val AVATARS = "avatars"
        const val INVITE_CODES = "inviteCodes"
    }

    /** 메시지 문서 필드 */
    object Field {
        const val TYPE = "type"
        const val BODY = "body"
        const val DICE_EXPR = "diceExpr"
        const val DICE_OUTCOME = "diceOutcome"
        const val SENDER_NAME = "senderName"
        const val SENDER_EMOJI = "senderEmoji"
        const val SENDER_IS_GM = "senderIsGm"
        const val SENDER_IS_BOT = "senderIsBot"
        const val SENDER_NAME_COLOR = "senderNameColor"
        const val SENDER_BUBBLE_COLOR = "senderBubbleColor"
        const val IS_OOC = "isOoc"
        const val CREATED_AT = "createdAt"
        const val EDITED_AT = "editedAt"
        const val AUTHOR_UID = "authorUid"
        const val AVATAR_ID = "avatarId"

        // 방 문서
        const val NAME = "name"
        const val ICON = "icon"
        const val INVITE_CODE = "inviteCode"
        const val THEME_COLOR = "themeColor"
        const val BACKGROUND_KEY = "backgroundKey"
        const val RULE = "rule"

        // 멤버·아바타·초대코드 문서
        const val FCM_TOKEN = "fcmToken"
        const val JOINED_AT = "joinedAt"
        const val UPDATED_AT = "updatedAt"
        const val DATA = "data"
        const val ROOM_ID = "roomId"
    }

    /** 메시지 타입 (Android는 enum, 데스크톱·와이어는 이 문자열) */
    object MessageType {
        const val TEXT = "TEXT"
        const val DICE = "DICE"
        const val SYSTEM = "SYSTEM"
    }

    /** 방 기본값 — 양 클라이언트가 같은 값을 써야 첫 화면이 갈라지지 않는다 */
    const val DEFAULT_BACKGROUND = "preset_lighthouse"
    const val DEFAULT_THEME_COLOR = 0xFF8EC5E8

    /** 배경 프리셋 키 접두 — 커스텀 배경(로컬 파일 경로)과 구분하는 기준 */
    const val PRESET_PREFIX = "preset_"

    /**
     * 아바타 축소 크기(긴 변, px). 양 모듈이 같아야 같은 이미지가 같은 해시로
     * 업로드된다 — 어긋나면 avatars 문서가 갈라진다.
     */
    const val AVATAR_MAX_PX = 256

    /** Firestore 배치 쓰기 한도(500)보다 여유 있게 */
    const val BATCH_SIZE = 450

    /** 초대 코드 — 헷갈리는 글자(0/O/1/I) 제외 */
    const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val INVITE_LENGTH = 6
}
