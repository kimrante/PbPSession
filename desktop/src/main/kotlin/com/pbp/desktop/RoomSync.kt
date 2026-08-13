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
    /**
     * 삭제한 문서 id — 폴이 되살리지 못하게 막는 목록. 끝없이 늘면 폴마다 도는
     * 대조 비용이 서서히 는다(DC8). 오래된 것부터 밀어내는 상한을 둔다 —
     * 억제가 필요한 것은 최근 삭제뿐이고, 그보다 옛 문서는 이미 서버에서 사라졌다.
     */
    val deletedDocIds: MutableSet<String> =
        object : java.util.LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                val added = super.add(element)
                while (size > DELETED_IDS_MAX) iterator().let { it.next(); it.remove() }
                return added
            }
        }
    /** 파일 캐시(P3 근본 수정)에서 이미 복원 시도했는지 */
    var diskLoaded: Boolean = false
    /** 마지막 파일 캐시 저장 시각 — 30초 스로틀 */
    var lastSavedAt: Long = 0L

    /**
     * 마지막으로 **적용한** logsClearedAt (H2). 방 문서의 값은 영구히 남아 있어서,
     * 이걸 기억해 두지 않으면 60초 메타 폴마다 같은 초기화를 다시 적용한다 —
     * 리셋 직후 상대가 보낸 메시지가 떴다 사라졌다를 반복했다.
     */
    var appliedClearedAt: Long = 0L

    private companion object {
        /** 2인 방의 한 세션에서 이보다 많이 지우는 일은 없다 */
        const val DELETED_IDS_MAX = 2_000
    }
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
    judgeTargetId: String? = null,
    judgeRef: String? = null,
): Map<String, Any?> = mapOf(
    // 폴 커서 전용 필드(syncAt)는 여기서 넣지 않는다 — 쓰기 시점에 **서버가 찍는다**
    // (FirestoreRest.postMessage의 updateTransforms, G2). 로컬 시계로 쓰면 PC 시계가
    // 몇 초만 빨라도 폴 커서가 서버 시각을 앞질러 상대 메시지를 건너뛴다.
    "type" to type,
    "judgeTarget" to judgeTarget,
    "judgeTargetId" to judgeTargetId,
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
    // 폴 커서 전용 syncAt은 쓰기 시점에 서버가 찍는다 (G2). 반드시 있어야 한다 —
    // 없으면 상대 데스크톱의 증분 질의에 안 걸려 참여 인사·초기화 안내가 영구 누락된다 (A5)
    "type" to Protocol.MessageType.SYSTEM,
    "body" to body,
    "createdAt" to System.currentTimeMillis(),
    "authorUid" to authorUid,
    "isOoc" to false, "senderIsGm" to false, "senderIsBot" to false,
)
