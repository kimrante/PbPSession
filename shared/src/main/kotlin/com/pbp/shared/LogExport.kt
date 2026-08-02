package com.pbp.shared

import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * HTML 로그 내보내기 (스펙 6장) — 모바일·데스크톱 공용 렌더러 (리뷰 A3).
 *
 * 밝은 종이 톤의 단일 UTF-8 HTML로, 앱 내 렌더링 규칙을 보존한다:
 * 원형 프로필 인장 · 이름 색(라이트 치환) · 시간 · 말풍선 색/좌우 형태 ·
 * GM 명조 서술 문단(" " 인용 분리) · 잡담의 회색 〔잡담〕 처리 · 마크다운/루비.
 *
 * 각 모듈은 자기 Message를 [ExportMessage]로 옮겨 담기만 한다 — 과거에는 이
 * 200줄이 축자 복제돼 있어 방 아이콘 헤더 같은 차이가 실제로 생겼다.
 */
object LogExport {

    /** 내보내기가 읽는 필드만 담은 렌더 모델 */
    data class ExportMessage(
        val type: String,
        val body: String,
        val createdAt: Long,
        val mine: Boolean,
        val senderName: String? = null,
        val senderEmoji: String? = null,
        val senderIsGm: Boolean = false,
        val senderNameColor: Long? = null,
        val senderBubbleColor: Long? = null,
        val senderTextColor: Long? = null,
        val isOoc: Boolean = false,
        val editedAt: Long? = null,
        val diceExpr: String? = null,
        val diceOutcome: String? = null,
        /** 아바타 조회 키 — 모바일은 로컬 경로, 데스크톱은 avatarId */
        val avatarKey: String? = null,
    )

    fun buildHtml(
        roomName: String,
        roomIcon: String,
        messages: List<ExportMessage>,
        /** 아바타 키 → data URI (없으면 null). 호출부가 캐시·네트워크를 담당 */
        avatarDataUri: (String) -> String?,
    ): String {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val avatarCache = mutableMapOf<String, String?>()

        fun avatarHtml(message: ExportMessage, extraStyle: String = ""): String {
            val uri = message.avatarKey?.let { key -> avatarCache.getOrPut(key) { avatarDataUri(key) } }
            return if (uri != null) {
                """<img class="ava" style="$extraStyle" src="$uri" alt="">"""
            } else {
                """<span class="ava" style="$extraStyle">${escape(message.senderEmoji ?: "🙂")}</span>"""
            }
        }

        val body = StringBuilder()
        var lastDayKey = ""
        for (message in messages) {
            val dayKey = ChatDates.dayKey(message.createdAt)
            if (dayKey != lastDayKey) {
                body.append("""<div class="day">${ChatDates.label(message.createdAt)}</div>""").append('\n')
                lastDayKey = dayKey
            }
            val time = timeFormat.format(Date(message.createdAt))
            val edited = if (message.editedAt != null) """ <i class="ed">(수정됨)</i>""" else ""
            when {
                message.type == Protocol.MessageType.SYSTEM ->
                    body.append("""<div class="sys"><span>${escape(message.body)}</span></div>""")

                // 판정 요청은 한 줄로만 — 종이 문서에서 버튼은 의미가 없고,
                // 굴림 결과는 어차피 뒤의 다이스 카드로 남는다 (J7)
                message.type == Protocol.MessageType.JUDGE ->
                    body.append("""<div class="sys"><span>${escape(message.body)}</span></div>""")

                message.type == Protocol.MessageType.DICE -> {
                    val outcome = Rules.outcomeLabel(message.diceOutcome)?.let { label ->
                        val color = if (Rules.isSuccess(message.diceOutcome)) "#2563C9" else "#C0392B"
                        """ <b style="color:$color">${escape(label)}</b>"""
                    } ?: ""
                    body.append("""<div class="dice">🎲 ${escape(message.diceExpr ?: "")} → <b>${escape(message.body)}</b>$outcome</div>""")
                }

                // GM 서술: 명조 문단 + " " 인용만 말풍선 분리
                message.senderIsGm && !message.isOoc -> {
                    for (part in GmSpeech.split(message.body)) {
                        when (part) {
                            is GmSpeech.Part.Narration -> {
                                val gmLabel = message.senderName
                                    ?.let { if (it.startsWith("GM")) it else "GM $it" } ?: "GM"
                                body.append(
                                    """<div class="lnarr">${markupHtml(part.text)}<br><span class="nmeta">${escape(gmLabel)} · 서술 — $time$edited</span></div>"""
                                )
                            }
                            is GmSpeech.Part.Quote -> body.append(
                                bubbleHtml(
                                    bodyHtml = markupHtml(part.text),
                                    name = "GM",
                                    nameColor = "#8A6D1C",
                                    bubbleColor = hex(Palette.gmQuoteBubbleForExport),
                                    mine = false,
                                    time = time,
                                    edited = edited,
                                    avatar = avatarHtml(message),
                                )
                            )
                        }
                        body.append('\n')
                    }
                }

                else -> {
                    val ooc = message.isOoc
                    val nameArgb = message.senderNameColor
                    val nameColor = when {
                        ooc -> "#A39A86"
                        nameArgb != null -> hex(Palette.nameColorForLight(nameArgb))
                        else -> "#3D3628"
                    }
                    val bubbleColor =
                        if (ooc) "#E3DED2" else hex(message.senderBubbleColor ?: Palette.bubblePresets.first())
                    val prefix = if (ooc) "〔잡담〕 " else ""
                    // 잡담이 아니면 문장 중간의 " " 대사를 별도 말풍선으로 분리 (앱 화면과 동일)
                    val parts = if (ooc) listOf(GmSpeech.Part.Narration(message.body))
                    else GmSpeech.split(message.body)
                    parts.forEachIndexed { index, part ->
                        val partText = when (part) {
                            is GmSpeech.Part.Narration -> part.text
                            is GmSpeech.Part.Quote -> "“${part.text}”"
                        }
                        body.append(
                            bubbleHtml(
                                bodyHtml = prefix + markupHtml(partText),
                                name = message.senderName ?: "",
                                nameColor = nameColor,
                                bubbleColor = bubbleColor,
                                textColor = message.senderTextColor?.let { hex(it) },
                                mine = message.mine,
                                time = if (index == parts.lastIndex) time else "",
                                edited = if (index == parts.lastIndex) edited else "",
                                avatar = avatarHtml(
                                    message,
                                    if (ooc) "filter:grayscale(.5);opacity:.7;" else "",
                                ),
                                ooc = ooc,
                            )
                        )
                        body.append('\n')
                    }
                }
            }
            body.append('\n')
        }

        return """<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(roomName)} — 세션 로그</title>
<style>
body{background:#E9E4D8;color:#2A2620;font-family:'Noto Sans KR','Malgun Gothic',sans-serif;margin:0;padding:24px 16px 64px;font-size:12px;line-height:1.6}
.wrap{max-width:720px;margin:0 auto}
.doc-h{font-family:'Gowun Batang','Batang',serif;text-align:center;border-bottom:2px solid #2A2620;padding-bottom:10px;margin-bottom:16px}
.doc-h h1{font-size:17px;margin:0;font-weight:700}
.doc-h p{font-size:9.5px;color:#8B8474;margin:3px 0 0}
.day{text-align:center;color:#A39A86;font-size:10px;letter-spacing:.08em;margin:20px 0 6px}
.sys{text-align:center;margin:10px 0}
.sys span{background:#E3DED2;color:#8B8474;font-size:10.5px;padding:3px 10px;border-radius:10px}
.dice{text-align:center;margin:10px auto;color:#8a6d1c;font-size:11px}
.lrow{display:flex;gap:8px;margin-bottom:11px;align-items:flex-start}
.lrow.me{flex-direction:row-reverse;text-align:right}
.ava{width:30px;height:30px;border-radius:50%;flex:none;background:#E2DCCB;display:flex;align-items:center;justify-content:center;font-size:12px;object-fit:cover;overflow:hidden}
.lname{font-size:10.5px;font-weight:700}
.lname time{font-size:9px;color:#A39A86;font-weight:400;margin-left:5px}
.ed{font-size:8.5px;color:#A39A86;font-style:normal}
.lbubble{display:inline-block;border-radius:3px 12px 12px 12px;padding:6px 10px;font-size:11.5px;margin-top:3px;color:#10151C;line-height:1.5;text-align:left;white-space:pre-wrap}
.lrow.me .lbubble{border-radius:12px 3px 12px 12px}
.lbubble.lchat{background:#E3DED2!important;color:#8B8474!important;border:1px dashed #C5BDA9}
.lnarr{font-family:'Gowun Batang','Batang',serif;font-size:11.5px;line-height:1.8;color:#3D3628;border-left:2px solid #C9A227;padding:2px 0 2px 12px;margin:4px 4px 12px;white-space:pre-wrap}
.lnarr .nmeta{font-family:'Noto Sans KR',sans-serif;font-size:8.5px;color:#A39A86}
ruby rt{font-size:7px;color:inherit}
</style>
</head>
<body>
<div class="wrap">
<div class="doc-h">
<h1>${(escape(roomIcon) + " ").takeIf { roomIcon.isNotEmpty() } ?: ""}${escape(roomName)}</h1>
<p>메시지 ${messages.size}개 · PbP에서 내보냄 · UTF-8</p>
</div>
$body</div>
</body>
</html>
"""
    }

    /** 마크다운·루비를 HTML로 (이스케이프 포함) */
    fun markupHtml(text: String): String = buildString {
        for (node in PbpMarkup.parse(text)) {
            when (node) {
                is PbpMarkup.Node.Span -> {
                    var html = escape(node.text)
                    if (node.bold) html = "<b>$html</b>"
                    if (node.italic) html = "<i>$html</i>"
                    if (node.strike) html = "<del>$html</del>"
                    append(html)
                }
                is PbpMarkup.Node.Value ->
                    append("""<b style="color:#2563C9">${escape(node.text)}</b>""")
                is PbpMarkup.Node.Ruby ->
                    append("<ruby>${escape(node.base)}<rt>${escape(node.ruby)}</rt></ruby>")
            }
        }
    }

    private fun bubbleHtml(
        bodyHtml: String,
        name: String,
        nameColor: String,
        bubbleColor: String,
        textColor: String? = null,
        mine: Boolean,
        time: String,
        edited: String,
        avatar: String,
        ooc: Boolean = false,
    ): String {
        val rowClass = if (mine) "lrow me" else "lrow"
        val bubbleClass = if (ooc) "lbubble lchat" else "lbubble"
        val ink = textColor?.let { ";color:$it" } ?: ""
        return """<div class="$rowClass">$avatar<div><div class="lname" style="color:$nameColor">${escape(name)}<time>$time</time>$edited</div><div class="$bubbleClass" style="background:$bubbleColor$ink">$bodyHtml</div></div></div>"""
    }

    fun hex(argb: Long): String = String.format("#%06X", argb and 0xFFFFFF)

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /** 이미지 바이트를 데이터 URI로. 매직 바이트로 형식을 판별한다 (P3-9) */
    fun bytesToDataUri(bytes: ByteArray): String? {
        val mime = sniffMime(bytes) ?: return null
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    /** 전체 시그니처 검사 — 짧은 접두 판별의 오탐으로 깨진 <img>가 생기지 않게 */
    private fun sniffMime(bytes: ByteArray): String? {
        fun startsWith(prefix: ByteArray, offset: Int = 0): Boolean =
            bytes.size >= offset + prefix.size &&
                prefix.indices.all { bytes[offset + it] == prefix[it] }
        return when {
            startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
            startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "image/jpeg"
            startsWith("GIF87a".toByteArray()) || startsWith("GIF89a".toByteArray()) -> "image/gif"
            startsWith("RIFF".toByteArray()) && startsWith("WEBP".toByteArray(), offset = 8) -> "image/webp"
            else -> null
        }
    }
}
