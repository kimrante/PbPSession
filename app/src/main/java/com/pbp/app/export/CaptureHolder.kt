package com.pbp.app.export

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pbp.app.data.Message

/**
 * 캡처 결과를 화면 사이에서 건네는 자리.
 *
 * 비트맵은 내비게이션 인자로 넘길 수 없다. ViewModel에 두는 방법은 쓸 수 없는데,
 * `viewModel()`이 **화면(NavBackStackEntry)마다 다른 저장소**를 보기 때문에 채팅 화면이
 * 만든 것을 미리보기 화면이 볼 수 없다(v0.7.0에서 미리보기가 비어 있던 원인).
 *
 * 회전에도 살아 있어야 하므로 프로세스 수명으로 둔다. 한 번에 한 벌만 들고 있고
 * 새로 그릴 때 이전 것을 정리한다.
 */
object CaptureHolder {

    /** 다시 그릴 때 필요한 원본 — 배경 포함 토글이 바뀌면 이걸로 재렌더한다 */
    data class Request(
        val roomName: String,
        val backgroundKey: String,
        val messages: List<Message>,
    )

    var request by mutableStateOf<Request?>(null)
        private set

    var pages by mutableStateOf<List<Bitmap>>(emptyList())
        private set

    fun set(request: Request, pages: List<Bitmap>) {
        clear()
        this.request = request
        this.pages = pages
    }

    fun clear() {
        pages.forEach { if (!it.isRecycled) it.recycle() }
        pages = emptyList()
        request = null
    }
}
