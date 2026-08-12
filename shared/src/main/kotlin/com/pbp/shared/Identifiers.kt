package com.pbp.shared

import java.security.SecureRandom

/**
 * 방 밖에서 들어오는 식별자를 만들고 검사하는 곳 (보안 리뷰 SV1·SV4·SV12).
 *
 * 모바일·데스크톱이 같은 규격을 써야 한다 — 한쪽만 느슨하면 그쪽이 뚫린다.
 */
object Identifiers {

    /** 초대 코드 한 글자를 뽑는 난수. 예측 가능한 Random.Default를 쓰면 안 된다 */
    private val secureRandom by lazy { SecureRandom() }

    /**
     * 새 초대 코드. 이 코드 하나가 방 전체를 여는 열쇠라 **암호학적 난수**로 뽑는다.
     * 예전에는 Kotlin Random.Default(비-CSPRNG)로 뽑았다 — 씨앗을 알면 다음 코드가
     * 예측된다.
     */
    fun newInviteCode(): String {
        val alphabet = Protocol.INVITE_ALPHABET
        return buildString(Protocol.INVITE_LENGTH) {
            repeat(Protocol.INVITE_LENGTH) { append(alphabet[secureRandom.nextInt(alphabet.length)]) }
        }
    }

    /**
     * 초대 코드로 쓸 수 있는 문자열인가. 쿼리·URL에 넣기 전에 거른다 —
     * 사람이 입력하는 값이라 따옴표·개행이 섞여 들어올 수 있다.
     */
    fun isValidInviteCode(code: String): Boolean =
        code.isNotEmpty() && code.length <= 32 && code.all { it in Protocol.INVITE_ALPHABET }

    /**
     * 아바타 id로 쓸 수 있는 값인가. 보내는 쪽은 언제나 md5 hex 32자라, 그 밖의 값은
     * 상대가 손으로 지어낸 것이다.
     *
     * **파일 이름과 URL 경로에 그대로 들어가는 값**이므로 반드시 먼저 통과시켜야 한다.
     * `..\..\Startup\evil.bat` 같은 값을 그대로 쓰면 상대가 정한 경로에 상대가 정한
     * 내용을 쓰게 된다.
     */
    fun isValidAvatarId(id: String): Boolean =
        id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }
}
