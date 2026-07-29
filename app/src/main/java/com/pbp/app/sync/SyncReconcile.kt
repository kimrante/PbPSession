package com.pbp.app.sync

/**
 * 리스너가 없던 사이의 서버 삭제를 로컬에 반영하는 규칙 (P1-1).
 *
 * 기준선은 **attach 시점**의 로컬 remoteId 집합이다. 그 뒤에 업로드된 메시지는
 * 기준선에 없으므로, 첫 서버 스냅샷이 그 메시지보다 먼저 만들어졌더라도
 * 삭제 후보가 되지 않는다 (R3).
 */
object SyncReconcile {

    fun deletedRemoteIds(baseline: Set<String>, serverIds: Set<String>): Set<String> =
        baseline - serverIds
}
