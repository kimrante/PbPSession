package com.pbp.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifiersTest {

    @Test
    fun `초대 코드는 정해진 길이와 글자만 쓴다`() {
        repeat(50) {
            val code = Identifiers.newInviteCode()
            assertEquals(Protocol.INVITE_LENGTH, code.length)
            assertTrue(code, code.all { it in Protocol.INVITE_ALPHABET })
        }
    }

    @Test
    fun `초대 코드는 매번 다르다`() {
        // 32^8 공간이라 50개가 겹치면 난수가 고장 난 것이다
        assertEquals(50, List(50) { Identifiers.newInviteCode() }.distinct().size)
    }

    @Test
    fun `이미 나눠 준 6자 코드도 통과한다`() {
        assertTrue(Identifiers.isValidInviteCode("ABC234"))
    }

    @Test
    fun `쿼리를 깨는 글자가 섞인 코드는 거른다`() {
        assertFalse(Identifiers.isValidInviteCode("""AB"C"""))
        assertFalse(Identifiers.isValidInviteCode("AB\\C"))
        assertFalse(Identifiers.isValidInviteCode("AB\nC"))
        assertFalse(Identifiers.isValidInviteCode("abc234")) // 소문자는 정규화 전
        assertFalse(Identifiers.isValidInviteCode(""))
    }

    @Test
    fun `아바타 id는 md5 hex만 받는다`() {
        assertTrue(Identifiers.isValidAvatarId("0123456789abcdef0123456789abcdef"))
        assertFalse(Identifiers.isValidAvatarId("0123456789ABCDEF0123456789ABCDEF")) // 대문자 아님
        assertFalse(Identifiers.isValidAvatarId("0123456789abcdef0123456789abcde")) // 31자
        assertFalse(Identifiers.isValidAvatarId(""))
    }

    @Test
    fun `경로를 벗어나려는 아바타 id는 막는다`() {
        assertFalse(Identifiers.isValidAvatarId("..\\..\\Startup\\evil.bat"))
        assertFalse(Identifiers.isValidAvatarId("../../etc/passwd"))
        assertFalse(Identifiers.isValidAvatarId("0123456789abcdef0123456789abcde/"))
    }
}
