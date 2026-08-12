package com.pbp.app.ui.common

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * DB·동기화 **쓰기**를 도는 코루틴 (Z3).
 *
 * `viewModelScope.launch { repo.… }`는 예외가 나면 그대로 앱을 죽인다. Room 쓰기는
 * `SQLiteFullException`(저장 공간 소진)·`SQLiteDatabaseCorruptException`을 던질 수
 * 있는데, 이미지와 캡처 PNG까지 쌓는 앱이라 공간 소진은 언젠가 실제로 온다.
 * 그때 증상이 정확히 **"메시지를 보내면 앱이 꺼진다"** 다.
 *
 * 수신 경로는 이미 문서 단위로 격리돼 있어(SyncManager) 발신·설정 쪽만 구멍이었다.
 *
 * **읽기 Flow는 이 함수의 대상이 아니다** — Room이 스스로 관리하고, 실패해도
 * 크래시가 아니라 재구독 문제라 성격이 다르다.
 */
internal fun ViewModel.safeLaunch(
    app: Application,
    block: suspend CoroutineScope.() -> Unit,
) = viewModelScope.launch(
    CoroutineExceptionHandler { _, error ->
        Log.w("Pbp", "작업 실패", error)
        // 토스트는 메인 스레드에서만
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                app,
                "작업을 완료하지 못했습니다 — 저장 공간을 확인해 주세요",
                Toast.LENGTH_SHORT,
            ).show()
        }
    },
    block = block,
)
