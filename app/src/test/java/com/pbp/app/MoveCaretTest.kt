package com.pbp.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.pbp.app.ui.chat.moveCaret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 블루투스 키보드의 방향키로 커서를 옮기는 계산.
 * 인덱스 산수라 눈으로는 틀린 곳을 못 찾는다 — 표로 못 박는다.
 */
class MoveCaretTest {

    private fun at(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    private fun move(text: String, cursor: Int, key: Key): Int? =
        moveCaret(at(text, cursor), key)?.selection?.start

    @Test
    fun `좌우로 한 칸씩`() {
        assertEquals(2, move("안녕하세요", 3, Key.DirectionLeft))
        assertEquals(4, move("안녕하세요", 3, Key.DirectionRight))
    }

    @Test
    fun `양 끝에서는 더 나가지 않는다`() {
        assertNull(move("안녕", 0, Key.DirectionLeft))
        assertNull(move("안녕", 2, Key.DirectionRight))
    }

    @Test
    fun `범위를 잡아 둔 상태에서는 커서만 접는다`() {
        val selected = TextFieldValue("안녕하세요", TextRange(1, 4))
        assertEquals(1, moveCaret(selected, Key.DirectionLeft)?.selection?.start)
        assertEquals(4, moveCaret(selected, Key.DirectionRight)?.selection?.start)
    }

    @Test
    fun `위아래는 같은 칸을 찾아간다`() {
        // "abc\ndefgh" — 둘째 줄 4번째 칸(인덱스 7)에서 위로 가면 첫 줄 같은 칸
        assertEquals(3, move("abc\ndefgh", 7, Key.DirectionUp))
        // 첫 줄 2번째 칸(인덱스 2)에서 아래로
        assertEquals(6, move("abc\ndefgh", 2, Key.DirectionDown))
    }

    @Test
    fun `윗줄이 짧으면 그 줄 끝까지만`() {
        // "ab\ncdefgh" — 둘째 줄 6번째 칸에서 위로 가면 첫 줄 끝(인덱스 2)
        assertEquals(2, move("ab\ncdefgh", 9, Key.DirectionUp))
    }

    @Test
    fun `첫 줄에서 위는 맨 앞으로, 마지막 줄에서 아래는 맨 뒤로`() {
        assertEquals(0, move("abc\ndef", 2, Key.DirectionUp))
        assertEquals(7, move("abc\ndef", 5, Key.DirectionDown))
    }

    @Test
    fun `한 줄뿐이면 위아래는 양 끝으로`() {
        assertEquals(0, move("abcdef", 3, Key.DirectionUp))
        assertEquals(6, move("abcdef", 3, Key.DirectionDown))
    }
}
