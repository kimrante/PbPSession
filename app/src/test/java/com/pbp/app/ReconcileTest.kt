package com.pbp.app

import com.pbp.app.sync.SyncReconcile
import org.junit.Assert.assertEquals
import org.junit.Test

/** 첫 스냅샷 삭제 대조 규칙 (P1-1 / R3) */
class ReconcileTest {

    @Test
    fun `기준선에 있는데 서버에 없는 문서만 삭제 대상`() {
        assertEquals(
            setOf("b"),
            SyncReconcile.deletedRemoteIds(baseline = setOf("a", "b"), serverIds = setOf("a", "c")),
        )
    }

    @Test
    fun `attach 이후 올린 메시지는 기준선에 없으므로 삭제되지 않는다`() {
        // baseline은 attach 시점 스냅샷 — 그 뒤 업로드된 "new"는 포함되지 않는다.
        // 서버 첫 스냅샷이 "new"보다 먼저 만들어져도 삭제 후보가 아니다 (R3)
        assertEquals(
            emptySet<String>(),
            SyncReconcile.deletedRemoteIds(baseline = setOf("a"), serverIds = setOf("a")),
        )
    }

    @Test
    fun `서버가 비어 있으면 기준선 전체가 삭제 대상 - 로그 초기화 수신`() {
        assertEquals(
            setOf("a", "b"),
            SyncReconcile.deletedRemoteIds(baseline = setOf("a", "b"), serverIds = emptySet()),
        )
    }
}
