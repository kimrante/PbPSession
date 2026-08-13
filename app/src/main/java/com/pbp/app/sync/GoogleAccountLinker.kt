package com.pbp.app.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.pbp.app.R
import kotlinx.coroutines.tasks.await

/**
 * 구글 계정 연결 — 폰과 PC가 같은 신원을 쓰기 위한 첫 단계.
 *
 * 지금 신원은 **기기마다 따로 만들어진 익명 계정**이다. 여기서는 그 익명 계정을
 * 버리지 않고 구글 계정을 **덧붙인다**(link). UID가 그대로라 지금 참여 중인 방과
 * 지난 대화가 하나도 어긋나지 않는다 — 새로 로그인하면 UID가 바뀌어 방에서
 * 떨어져 나간다.
 *
 * 한 구글 계정에 덧붙일 수 있는 익명 계정은 하나뿐이다. 이미 딸린 계정이 있으면
 * 덧붙이기가 실패하는데, 거기서 멈추면 **영영 연결되지 않는다** — 앱을 지웠다 깔거나
 * 다른 기기가 먼저 그 계정을 가져간 뒤가 그렇다. 그때는 그 계정으로 로그인해 원래
 * 신원을 되찾는다([Result.Recovered]). 지금 익명 계정은 버려지므로, 부르는 쪽은
 * 방 멤버 재등록까지 함께 해야 한다.
 */
internal class GoogleAccountLinker(
    private val context: Context,
    private val firebaseApp: () -> FirebaseApp,
) {
    sealed interface Result {
        /** 연결 성공. UID는 그대로다 */
        data class Linked(val email: String?) : Result

        /**
         * 이 구글 계정은 이미 다른 Firebase 계정에 붙어 있었다 — 그쪽으로 **되돌아갔다**.
         * 덧붙이기는 실패했지만 신원은 원래 것을 되찾은 상태다.
         */
        data class Recovered(val email: String?, val uid: String) : Result

        /** 사용자가 계정 선택을 닫았다 — 실패로 알릴 일이 아니다 */
        data object Cancelled : Result

        /** 기기에 구글 계정이 없다 */
        data object NoAccount : Result

        data class Failed(val message: String) : Result
    }

    private fun auth(): FirebaseAuth = FirebaseAuth.getInstance(firebaseApp())

    /** 구글 계정이 아직 붙지 않은 상태인가 */
    val isAnonymous: Boolean
        get() = runCatching { auth().currentUser?.isAnonymous ?: true }.getOrDefault(true)

    /** 연결된 구글 계정 주소. 연결 전이면 null */
    val linkedEmail: String?
        get() = runCatching {
            auth().currentUser
                ?.providerData
                ?.firstOrNull { it.providerId == GoogleAuthProvider.PROVIDER_ID }
                ?.email
        }.getOrNull()

    /**
     * 계정 선택 화면을 띄우고, 고른 구글 계정을 지금 익명 계정에 덧붙인다.
     *
     * @param activity 계정 선택 UI를 띄울 화면. 애플리케이션 컨텍스트로는 뜨지 않는다
     */
    suspend fun link(activity: Activity): Result {
        val idToken = when (val token = requestGoogleIdToken(activity)) {
            is TokenResult.Ok -> token.idToken
            is TokenResult.Problem -> return token.result
        }
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        return runCatching {
            val auth = auth()
            // 익명 계정이 아직 없으면 먼저 만든다 — 덧붙일 대상이 있어야 UID가 유지된다
            val user = auth.currentUser ?: auth.signInAnonymously().await().user
                ?: return Result.Failed("익명 계정을 만들지 못했습니다")
            val linked = user.linkWithCredential(credential).await().user
            Result.Linked(linked?.email)
        }.getOrElse { error ->
            when (error) {
                // 이 구글 계정에 이미 Firebase 계정이 딸려 있다 — 앱을 지웠다 깔거나
                // 다른 기기가 먼저 가져간 경우다. 여기서 멈추면 영영 연결이 안 되므로,
                // **그 계정으로 로그인해 원래 신원을 되찾는다.** 지금 익명 계정은 버려진다
                is FirebaseAuthUserCollisionException -> recover(credential)
                // [2단계]는 구글 토큰을 받은 뒤 Firebase에 붙이는 구간이다 —
                // 여기서 실패하면 Firebase 프로젝트 설정 쪽 문제다
                else -> Result.Failed(stageMessage("2단계 Firebase 연결", error))
            }
        }
    }

    /** 이미 이 구글 계정에 딸린 Firebase 계정으로 로그인한다 */
    private suspend fun recover(credential: com.google.firebase.auth.AuthCredential): Result =
        runCatching {
            val user = auth().signInWithCredential(credential).await().user
                ?: return Result.Failed("계정을 되찾지 못했습니다")
            Result.Recovered(user.email, user.uid)
        }.getOrElse { error ->
            Result.Failed(error.message ?: "계정을 되찾지 못했습니다")
        }

    /**
     * 실패가 어느 구간에서 났는지까지 남긴다.
     *
     * 계정 선택(Play 서비스)과 Firebase 연결은 원인이 전혀 다른데 화면에 뜨는 문구가
     * 비슷해서, 구간과 예외 종류를 알려 주지 않으면 어디를 고쳐야 할지 알 수 없다.
     */
    private fun stageMessage(stage: String, error: Throwable): String =
        "[$stage] ${error.javaClass.simpleName}: ${error.message ?: "메시지 없음"}"

    private sealed interface TokenResult {
        data class Ok(val idToken: String) : TokenResult
        data class Problem(val result: Result) : TokenResult
    }

    /** 시스템 계정 선택 UI로 구글 ID 토큰을 받는다 */
    private suspend fun requestGoogleIdToken(activity: Activity): TokenResult = runCatching {
        val option = GetSignInWithGoogleOption
            .Builder(context.getString(R.string.firebase_web_client_id))
            .build()
        val response = CredentialManager.create(activity).getCredential(
            activity,
            GetCredentialRequest.Builder().addCredentialOption(option).build(),
        )
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            TokenResult.Ok(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else {
            TokenResult.Problem(Result.Failed("구글 계정 정보를 받지 못했습니다"))
        }
    }.getOrElse { error ->
        TokenResult.Problem(
            when (error) {
                is GetCredentialCancellationException -> Result.Cancelled
                is NoCredentialException -> Result.NoAccount
                // [1단계]는 기기의 Play 서비스가 구글 토큰을 내주는 구간이다 —
                // 여기서 실패하면 이 앱(패키지+서명)이 OAuth 클라이언트에 없다는 뜻이 대부분
                else -> Result.Failed(stageMessage("1단계 계정 선택", error))
            }
        )
    }
}
