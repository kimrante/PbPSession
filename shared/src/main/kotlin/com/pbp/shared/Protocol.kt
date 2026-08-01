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
        const val SENDER_TEXT_COLOR = "senderTextColor"
        const val IS_OOC = "isOoc"
        const val CREATED_AT = "createdAt"

        /**
         * 서버에 실제로 기록된 시각 — **폴링 커서 전용**이다. 표시·정렬은 [CREATED_AT]
         * 그대로 쓴다.
         *
         * createdAt은 작성한 기기의 시계라, 오프라인에서 쓴 메시지가 몇 시간 뒤에
         * 커밋되면 커서보다 과거 시각으로 도착해 데스크톱 폴이 영영 못 본다(V1).
         * 이 필드는 커밋 시점에 정해지므로 그런 역전이 없다.
         *
         * **타입은 타임스탬프로 통일**해야 한다 — Firestore는 타입이 다르면 범위 질의가
         * 아예 걸리지 않아, 한쪽이 정수로 쓰면 그 메시지는 조회에서 통째로 빠진다.
         */
        const val SYNC_AT = "syncAt"
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
        const val PLATFORM = "platform"
        const val LAST_READ_AT = "lastReadAt"
        const val TYPING_UNTIL = "typingUntil"
        const val TYPING_NAME = "typingName"
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

    /**
     * 멤버 문서의 기기 종류. 읽음 확인은 모바일끼리만 성립하므로
     * (데스크톱은 lastReadAt을 쓰지 않는다) 상대가 어느 쪽인지 알아야 한다.
     */
    object Platform {
        const val ANDROID = "android"
        const val DESKTOP = "desktop"
    }

    /**
     * 입력 중 표시 — 실제 입력 이벤트가 있을 때만 [TYPING_THROTTLE_MS] 간격으로
     * `typingUntil = now + TYPING_TTL_MS`를 쓴다. 손을 멈추면 아무것도 쓰지 않고
     * 시각이 지나 저절로 꺼진다("가만히 있는 것"은 입력 중이 아니다).
     *
     * TTL은 스로틀보다 넉넉해야 한다 — 같으면 연달아 치는 중에도 한 번씩 꺼진다.
     */
    const val TYPING_THROTTLE_MS = 6_000L
    const val TYPING_TTL_MS = 9_000L

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
