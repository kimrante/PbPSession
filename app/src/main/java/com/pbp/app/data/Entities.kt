package com.pbp.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.pbp.app.ui.theme.PbpPalette

enum class MessageType { TEXT, DICE, SYSTEM }

@Entity(tableName = "rooms")
data class ChatRoom(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "🎲",
    val activeProfileId: Long? = null,
    val createdAt: Long,
    /** Firestore 방 문서 ID. null이면 로컬 전용(공유 안 됨) */
    val remoteId: String? = null,
    /** 상대를 초대할 때 쓰는 6자리 코드 */
    val inviteCode: String? = null,
    /** 마스터 지정 테마 컬러(ARGB). 전송 버튼·강조선·목록 인디케이터에 적용 */
    val themeColor: Long = PbpPalette.DEFAULT_THEME_COLOR,
    /** 배경: 프리셋 key(preset_*) 또는 갤러리 이미지의 로컬 파일 경로 */
    val backgroundKey: String = PbpPalette.DEFAULT_BACKGROUND,
    /** 방 생성자(마스터) 여부. 테마·배경 변경 권한 */
    val isMaster: Boolean = true,
    /** 이 시각 이후의 수신 메시지가 '미확인'(옐로 배지·푸시 대상) */
    val lastReadAt: Long = 0,
)

/**
 * 캐릭터 프로필. roomId가 null이면 모든 방에서 쓸 수 있는 전역 캐릭터,
 * null이 아니면 그 방에 귀속된 프로필(GM)이다. GM은 방 생성 시 정확히 1개 만들어진다.
 */
@Entity(
    tableName = "profiles",
    foreignKeys = [ForeignKey(
        entity = ChatRoom::class,
        parentColumns = ["id"],
        childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("roomId")],
)
data class CharacterProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val tagline: String = "",
    val emoji: String = "🙂",
    val imagePath: String? = null,
    val isGm: Boolean = false,
    val roomId: Long? = null,
    /** 이름 색(ARGB). null이면 기본 잉크색 */
    val nameColor: Long? = null,
    /** 말풍선 색(ARGB). null이면 기본 프리셋 첫 색 */
    val bubbleColor: Long? = null,
    /** 캐릭터별 value 목록(ProfileStats 인코딩). 메시지의 {값이름}이 값으로 치환된다 */
    val stats: String = "",
)

/**
 * 메시지는 발신 시점의 프로필 이름·이미지·색을 스냅샷으로 저장한다.
 * 이후 캐릭터를 수정해도 과거 로그의 인물은 바뀌지 않는다.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ChatRoom::class,
        parentColumns = ["id"],
        childColumns = ["roomId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("roomId")],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    val type: MessageType,
    val body: String,
    val diceExpr: String? = null,
    /** 다이스 비교식 판정 결과: "success" | "fail" | null(판정 없음) */
    val diceOutcome: String? = null,
    val senderName: String? = null,
    val senderEmoji: String? = null,
    val senderImagePath: String? = null,
    val senderIsGm: Boolean = false,
    val senderIsBot: Boolean = false,
    val senderNameColor: Long? = null,
    val senderBubbleColor: Long? = null,
    val createdAt: Long,
    /** Firestore 메시지 문서 ID. 수신 중복 방지와 수정/삭제 전파에 쓴다 */
    val remoteId: String? = null,
    /** 잡담 토글 ON 상태로 보낸 메시지 — 회색 점선 말풍선 */
    val isOoc: Boolean = false,
    /** 마지막 수정 시각. null이면 수정된 적 없음 */
    val editedAt: Long? = null,
    /** 상대에게서 수신한 메시지 여부(미확인 배지·알림 판정) */
    val incoming: Boolean = false,
)

class Converters {
    @TypeConverter
    fun fromMessageType(type: MessageType): String = type.name

    @TypeConverter
    fun toMessageType(name: String): MessageType = MessageType.valueOf(name)
}
