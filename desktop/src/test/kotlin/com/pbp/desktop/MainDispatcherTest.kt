package com.pbp.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IO에서 일한 뒤 UI 상태를 바꾸는 자리마다 `withContext(Dispatchers.Main)`을 쓴다.
 * 그런데 JVM에서 Dispatchers.Main은 **구현 모듈이 클래스패스에 있어야** 존재한다 —
 * 없으면 쓰는 순간 예외가 나고, 코루틴 안이라 화면에는 아무 일도 없던 것처럼 보인다
 * (방 참여·코드 발급·내보내기 결과가 조용히 사라졌다).
 *
 * 의존성 하나로 살고 죽는 일이라 테스트로 못 박아 둔다.
 */
class MainDispatcherTest {

    @Test
    fun `Dispatchers_Main이 AWT 이벤트 스레드로 붙는다`() = runBlocking {
        withContext(Dispatchers.Main) {
            assertTrue(
                "Dispatchers.Main이 UI 스레드가 아니다",
                java.awt.EventQueue.isDispatchThread(),
            )
        }
    }
}
