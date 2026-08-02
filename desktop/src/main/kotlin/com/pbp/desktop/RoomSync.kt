package com.pbp.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.type
import com.pbp.desktop.data.FirestoreRest
import com.pbp.desktop.data.Message
import com.pbp.desktop.data.Profile
import com.pbp.shared.Protocol

/** 방 동기화 상태·메시지 문서 조립 — Main.kt에서 분리 (리뷰 B1) */

/** 방별 세션 캐시 (P3) — 메시지 목록·증분 커서·삭제 억제 목록. 프로세스 수명 동안 유지 */
internal class RoomSession {
    var messages: List<Message> = emptyList()
    var lastCreatedAt: Long = 0L
    val deletedDocIds: MutableSet<String> = mutableSetOf()
    /** 파일 캐시(P3 근본 수정)에서 이미 복원 시도했는지 */
    var diskLoaded: Boolean = false
    /** 마지막 파일 캐시 저장 시각 — 30초 스로틀 */
    var lastSavedAt: Long = 0L
}


internal fun messageValues(
    type: String,
    body: String,
    sender: Profile,
    isOoc: Boolean,
    authorUid: String,
    diceExpr: String? = null,
    isBot: Boolean = false,
    diceOutcome: String? = null,
    avatarId: String? = null,
    judgeTarget: String? = null,
    judgeRef: String? = null,
): Map<String, Any?> = mapOf(
    // 폴 커서 전용 필드 — 안드로이드는 서버 타임스탬프로, 데스크톱은 지금 시각으로 쓴다.
    // REST 쓰기는 즉시 커밋이라 둘이 같은 의미다 (V1). 타입은 반드시 타임스탬프.
    "syncAt" to FirestoreRest.ServerTime(System.currentTimeMillis()),
    "type" to type,
    "judgeTarget" to judgeTarget,
    "judgeRef" to judgeRef,
    "body" to body,
    "diceExpr" to diceExpr,
    "diceOutcome" to diceOutcome,
    "senderName" to sender.name,
    "senderEmoji" to sender.emoji,
    "senderIsGm" to sender.isGm,
    "senderIsBot" to isBot,
    "senderNameColor" to sender.nameColor,
    "senderBubbleColor" to sender.bubbleColor,
    "senderTextColor" to sender.textColor,
    "isOoc" to isOoc,
    "createdAt" to System.currentTimeMillis(),
    "authorUid" to authorUid,
    "avatarId" to avatarId,
)

// ══════════════ 왼쪽 패널: 방 목록 ══════════════

/**
 * SYSTEM 안내 메시지 문서 — 프로필 전환·로그 초기화·참여 인사가 같은 스키마를 쓰도록 (리뷰 C2).
 * 스키마가 바뀌어도 데스크톱은 이 한 곳만 고치면 된다.
 */
internal fun systemMessageValues(body: String, authorUid: String): Map<String, Any?> = mapOf(
    // 폴 커서 전용 — 없으면 상대 데스크톱의 syncAt 증분 질의에 아예 안 걸려
    // 참여 인사·초기화 안내가 영구 누락된다 (A5)
    "syncAt" to FirestoreRest.ServerTime(System.currentTimeMillis()),
    "type" to Protocol.MessageType.SYSTEM,
    "body" to body,
    "createdAt" to System.currentTimeMillis(),
    "authorUid" to authorUid,
    "isOoc" to false, "senderIsGm" to false, "senderIsBot" to false,
)
