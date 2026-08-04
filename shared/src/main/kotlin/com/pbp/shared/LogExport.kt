package com.pbp.shared

import java.util.Base64

/**
 * HTML 로그 내보내기 (스펙 6장) — 모바일·데스크톱 공용 렌더러 (리뷰 A3).
 *
 * 밝은 종이 톤의 단일 UTF-8 HTML로, 앱 내 렌더링 규칙을 보존한다:
 * 원형 프로필 인장 · 이름 색(라이트 치환) · 말풍선 색/좌우 형태 ·
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
        val avatarCache = mutableMapOf<String, String?>()
        val shown = visible(messages)

        fun avatarHtml(message: ExportMessage, extraStyle: String = ""): String {
            val uri = message.avatarKey?.let { key -> avatarCache.getOrPut(key) { avatarDataUri(key) } }
            return if (uri != null) {
                """<img class="ava" style="$extraStyle" src="$uri" alt="">"""
            } else {
                """<span class="ava" style="$extraStyle">${escape(message.senderEmoji ?: "🙂")}</span>"""
            }
        }

        val body = StringBuilder()
        for (message in shown) {
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
                                    """<div class="lnarr">${markupHtml(part.text)}<br><span class="nmeta">${escape(gmLabel)} · 서술$edited</span></div>"""
                                )
                            }
                            is GmSpeech.Part.Quote -> body.append(
                                bubbleHtml(
                                    bodyHtml = markupHtml(part.text),
                                    name = "GM",
                                    nameColor = "#8A6D1C",
                                    bubbleColor = hex(Palette.gmQuoteBubbleForExport),
                                    mine = false,
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
.sys{text-align:center;margin:10px 0}
.sys span{background:#E3DED2;color:#8B8474;font-size:10.5px;padding:3px 10px;border-radius:10px}
.dice{text-align:center;margin:10px auto;color:#8a6d1c;font-size:11px}
.lrow{display:flex;gap:8px;margin-bottom:11px;align-items:flex-start}
.lrow.me{flex-direction:row-reverse;text-align:right}
.ava{width:30px;height:30px;border-radius:50%;flex:none;background:#E2DCCB;display:flex;align-items:center;justify-content:center;font-size:12px;object-fit:cover;overflow:hidden}
.lname{font-size:10.5px;font-weight:700}
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
<p>${escape(rangeLabel(shown))}메시지 ${shown.size}개 · PbP에서 내보냄 · UTF-8</p>
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
        edited: String,
        avatar: String,
        ooc: Boolean = false,
    ): String {
        val rowClass = if (mine) "lrow me" else "lrow"
        val bubbleClass = if (ooc) "lbubble lchat" else "lbubble"
        val ink = textColor?.let { ";color:$it" } ?: ""
        return """<div class="$rowClass">$avatar<div><div class="lname" style="color:$nameColor">${escape(name)}$edited</div><div class="$bubbleClass" style="background:$bubbleColor$ink">$bodyHtml</div></div></div>"""
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

    // ── 서식 없는 텍스트 내보내기 ───────────────────────────

    /**
     * 서식이 전혀 없는 .txt 로그.
     *
     * HTML판이 "보기 좋은 기록"이라면 이쪽은 **다른 도구에 붙여 넣기 위한 원문**이다.
     * 색·말풍선·아바타를 버리고 날짜·시각·화자·본문만 남긴다. 마크다운 기호도
     * 지운다(`**굵게**` → `굵게`) — 서식이 없는 파일에 기호만 남으면 잡음이다.
     *
     * GM 서술은 인용을 따로 떼지 않는다. 종이에서는 문단이 곧 서술이고,
     * 대사는 따옴표로 이미 구분되기 때문이다.
     */
    fun buildText(roomName: String, messages: List<ExportMessage>): String {
        val lf = "\n"
        val shown = visible(messages)
        val out = StringBuilder()
        out.appendLine(roomName)
        out.appendLine("${rangeLabel(shown)}${shown.size}개 메시지 · PbP 대화 기록")
        out.appendLine()
        for (message in shown) {
            val edited = if (message.editedAt != null) " (수정됨)" else ""
            val name = message.senderName?.takeIf { it.isNotBlank() } ?: "이름 없음"
            when {
                message.type == Protocol.MessageType.SYSTEM ||
                    message.type == Protocol.MessageType.JUDGE ->
                    out.appendLine("[${plain(message.body)}]")

                message.type == Protocol.MessageType.DICE -> {
                    val outcome = Rules.outcomeLabel(message.diceOutcome)?.let { " ($it)" } ?: ""
                    out.appendLine("🎲 ${message.diceExpr ?: ""} → ${plain(message.body)}$outcome")
                }

                message.isOoc ->
                    out.appendLine("($name · 잡담) ${plain(message.body)}$edited")

                else -> {
                    // 여러 줄 본문은 두 번째 줄부터 들여쓴다 — 안 그러면 다음 화자처럼 보인다
                    val indent = " ".repeat(4)
                    out.appendLine("$name: ${plain(message.body).replace(lf, lf + indent)}$edited")
                }
            }
        }
        return out.toString()
    }

    /**
     * 내보내기에 실을 메시지 — 로그 초기화 안내는 뺀다.
     *
     * 초기화 안내는 대화가 아니라 운영 기록이다. 채팅 화면에는 "여기서 로그가 끊겼다"는
     * 신호로 쓸모가 있지만, 내보낸 파일에서는 그 위에 아무것도 없으니 잡음만 된다.
     */
    private fun visible(messages: List<ExportMessage>): List<ExportMessage> =
        messages.filterNot {
            it.type == Protocol.MessageType.SYSTEM && it.body == Protocol.Notice.LOGS_RESET
        }

    /**
     * 제목 아래 기록 범위 — "2026-07-28 – 2026-08-01 · " (비었으면 빈 문자열).
     * 캡처 머리글과 같은 함수를 써서 두 산출물이 같은 문구를 낸다.
     */
    private fun rangeLabel(messages: List<ExportMessage>): String =
        if (messages.isEmpty()) "" else {
            CaptureLayout.formatDateRange(
                messages.first().createdAt, messages.last().createdAt,
            ) + " · "
        }

    /** 마크업 기호를 걷어낸 본문 — 루비는 "본문(독음)"으로 편다 */
    internal fun plain(body: String): String = buildString {
        for (node in PbpMarkup.parse(body)) {
            when (node) {
                is PbpMarkup.Node.Span -> append(node.text)
                is PbpMarkup.Node.Value -> append(node.text)
                is PbpMarkup.Node.Ruby -> append(node.base).append('(').append(node.ruby).append(')')
            }
        }
    }
}
