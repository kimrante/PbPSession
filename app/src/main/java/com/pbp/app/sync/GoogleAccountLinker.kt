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
 * 한 구글 계정에 덧붙일 수 있는 익명 계정은 하나뿐이다. 두 번째 기기는 덧붙이지
 * 못하고 '갈아타야' 하는데, 그 이관은 다음 단계에서 다룬다 — 지금은 [Result.AlreadyLinked]로
 * 분명히 알리고 아무것도 건드리지 않는다.
 */
internal class GoogleAccountLinker(
    private val context: Context,
    private val firebaseApp: () -> FirebaseApp,
) {
    sealed interface Result {
        /** 연결 성공. UID는 그대로다 */
        data class Linked(val email: String?) : Result

        /** 이 구글 계정은 이미 다른 기기의 계정에 붙어 있다 (기기 간 이어하기는 다음 단계) */
        data object AlreadyLinked : Result

        /** 사용자가 계정 선택을 닫았다 — 실패로 알릴 일이 아니다 */
        data object Cancelled : Result

        /** 기기에 구글 계정이 없다 */
        data object NoAccount : Result

        data class Failed(val message: String) : Result
    }

    private fun auth(): FirebaseAuth = FirebaseAuth.getInstance(firebaseApp())

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
                is FirebaseAuthUserCollisionException -> Result.AlreadyLinked
                else -> Result.Failed(error.message ?: "연결에 실패했습니다")
            }
        }
    }

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
                else -> Result.Failed(error.message ?: "계정 선택에 실패했습니다")
            }
        )
    }
}
