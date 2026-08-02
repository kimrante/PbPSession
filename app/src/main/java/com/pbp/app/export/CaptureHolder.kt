package com.pbp.app.export

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
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

    /**
     * 재렌더용 스코프 — 화면 스코프를 쓰면 회전으로 취소돼 설정만 바뀌고 이미지는
     * 옛 상태로 남는다 (R7). 캡처는 화면보다 오래 사는 작업이다.
     */
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    /**
     * 지금 화면에 떠 있는 액티비티 (A2).
     *
     * 렌더는 액티비티의 decorView에 오프스크린 뷰를 붙여야 컴포지션이 돈다. 클릭 시점의
     * 액티비티를 붙들고 있으면 도중에 회전했을 때 **파괴된 액티비티의 분리된 decorView**에
     * 붙어 컴포지션이 시작되지 않고 "높이 0"으로 실패한다. 그래서 붙이기 직전에 여기서
     * 최신 것을 꺼낸다. 강한 참조로 들면 액티비티가 통째로 샌다 — 약참조로 둔다.
     */
    private var activityRef: java.lang.ref.WeakReference<ComponentActivity>? = null

    val activity: ComponentActivity? get() = activityRef?.get()

    fun bind(activity: ComponentActivity) {
        activityRef = java.lang.ref.WeakReference(activity)
    }

    /**
     * 렌더·저장·공유가 진행 중인가 (A2). 화면 로컬 remember에 두면 회전 뒤 false로
     * 초기화돼 이전 작업이 도는 중에 저장·재렌더가 겹쳤다.
     */
    var busy by mutableStateOf(false)

    /** 다시 그릴 때 필요한 원본 — 배경 포함 토글이 바뀌면 이걸로 재렌더한다 */
    data class Request(
        val roomName: String,
        val backgroundKey: String,
        val messages: List<Message>,
        /** 방 테마색 — 이미지의 시간 표기 색이 화면과 같아야 한다 (E6) */
        val themeColor: Long,
        /**
         * 굴림이 끝난 요청 키 (E6) — **고른 범위가 아니라 방 전체** 기준이다.
         * 굴림 결과는 범위 밖에 있을 수 있는데, 범위 안만 보면 완료된 판정이 ⋯로 찍힌다.
         */
        val rolledRefs: Set<String>,
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

    /**
     * 참조만 놓는다 — 즉시 recycle하지 않는다. 공유 압축처럼 아직 이 비트맵을 읽고 있는
     * 코루틴이 있을 수 있고, 화면의 Image 노드도 한 프레임 늦게 사라진다 (R4).
     * 회수는 GC에 맡긴다.
     */
    fun clear() {
        pages = emptyList()
        request = null
    }
}
