package com.davi.helper

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.davi.helper.databinding.ActivityLoginBinding
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.user.UserApiClient
import java.util.UUID
import java.util.concurrent.Executors

/**
 * ON-1 로그인 (docs/SCREENS.md). 릴리스 진입점.
 *
 * 로그인은 필수 관문이 아니다 — 어떤 실패도 앱 사용을 막지 않고 "로그인 없이 시작"이
 * 항상 열려 있다. 이미 결정(유효 세션 또는 스킵)이 있으면 화면 없이 바로 온보딩으로 간다.
 * 카카오 SDK 흐름은 카카오톡 앱 우선, 실패 시 계정(웹) 로그인 폴백이며 **사용자 취소는
 * 폴백하지 않는다**(취소했는데 웹뷰가 또 뜨는 것을 막는다 — 카카오 공식 가이드 패턴).
 *
 * 카카오 access token과 세션 토큰은 어떤 로그에도 남기지 않는다(루트 CLAUDE.md).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authStore: AuthStore
    private val authExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val authClient by lazy {
        KakaoAuthApiClient(
            BuildConfig.SERVER_URL,
            devAccessToken = BuildConfig.DEV_ACCESS_TOKEN,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authStore = AuthStore(this)
        if (authStore.hasDecision(nowEpochSeconds())) {
            proceedToOnboarding()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ edge-to-edge: 시스템 바 인셋을 마진으로 반영(#78 패턴)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = bars.left
                topMargin = bars.top
                rightMargin = bars.right
                bottomMargin = bars.bottom
            }
            WindowInsetsCompat.CONSUMED
        }

        // 네이티브 앱 키가 없는 빌드(키 미설정 개발 환경·현 release)는 SDK가 init되지 않았다.
        // 카카오 경로를 숨기고 스킵만 노출한다 — 로그인 실패가 앱을 막지 않는다는 원칙의 빌드판.
        binding.btnKakaoLogin.isVisible = BuildConfig.KAKAO_NATIVE_APP_KEY.isNotEmpty()

        binding.btnKakaoLogin.setOnClickListener { startKakaoLogin() }
        binding.btnLoginSkip.setOnClickListener {
            authStore.markSkipped()
            proceedToOnboarding()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        authExecutor.shutdown()
    }

    private fun startKakaoLogin() {
        setInProgress(true)
        val client = UserApiClient.instance
        if (client.isKakaoTalkLoginAvailable(this)) {
            client.loginWithKakaoTalk(this) { token, error ->
                when {
                    // 사용자가 스킵으로 이미 떠났으면 늦은 SDK 결과는 버린다.
                    isFinishing || isDestroyed -> {}
                    error is ClientError && error.reason == ClientErrorCause.Cancelled ->
                        setInProgress(false)
                    error != null -> client.loginWithKakaoAccount(this, callback = accountCallback)
                    token != null -> exchangeWithServer(token)
                    else -> showError(R.string.login_error_auth)
                }
            }
        } else {
            client.loginWithKakaoAccount(this, callback = accountCallback)
        }
    }

    private val accountCallback: (OAuthToken?, Throwable?) -> Unit = { token, error ->
        when {
            isFinishing || isDestroyed -> {}
            error is ClientError && error.reason == ClientErrorCause.Cancelled ->
                setInProgress(false)
            error != null -> {
                Log.i(TAG, "kakao account login failed: ${error.javaClass.simpleName}")
                showError(R.string.login_error_auth)
            }
            token != null -> exchangeWithServer(token)
            else -> showError(R.string.login_error_auth)
        }
    }

    private fun exchangeWithServer(token: OAuthToken) {
        if (isFinishing || isDestroyed) return
        binding.loginStatus.setText(R.string.login_in_progress)
        binding.loginStatus.isVisible = true
        val request = KakaoLoginRequest(
            kakaoAccessToken = token.accessToken,
            clientVersion = BuildConfig.VERSION_NAME,
        )
        val logicalRequest = LogicalRequestAttempt(UUID.randomUUID().toString(), attempt = 1)
        authExecutor.execute {
            val result = authClient.login(request, logicalRequest)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                onExchangeResult(result)
            }
        }
    }

    private fun onExchangeResult(result: KakaoLoginResult) {
        when (result) {
            is KakaoLoginResult.Success -> {
                authStore.saveSession(
                    result.response.sessionToken,
                    result.response.expiresInSeconds,
                    nowEpochSeconds(),
                )
                Log.i(TAG, "kakao login ok requestId=${result.trace.requestId}")
                proceedToOnboarding()
            }
            is KakaoLoginResult.ApiFailure -> {
                Log.i(TAG, "kakao login apiFailure kind=${result.error.kind} status=${result.statusCode}")
                showError(
                    when (result.error.kind) {
                        ApiErrorKind.NETWORK -> R.string.login_error_network
                        ApiErrorKind.AUTH -> R.string.login_error_auth
                        else -> R.string.login_error_server
                    },
                )
            }
            is KakaoLoginResult.NetworkFailure -> {
                Log.i(TAG, "kakao login networkFailure")
                showError(R.string.login_error_network)
            }
            is KakaoLoginResult.HttpFailure -> {
                Log.i(TAG, "kakao login httpFailure status=${result.statusCode}")
                showError(R.string.login_error_server)
            }
            is KakaoLoginResult.ParseFailure -> {
                Log.i(TAG, "kakao login parseFailure")
                showError(R.string.login_error_server)
            }
        }
    }

    private fun setInProgress(inProgress: Boolean) {
        // 스킵은 절대 잠그지 않는다(#168 리뷰 P2) — "로그인 없이 시작"은 서버·카카오가
        // 느리거나 죽어도 항상 열려 있어야 한다. 스킵하면 진행 중 요청의 늦은 결과는
        // isFinishing 가드가 버린다.
        binding.btnKakaoLogin.isEnabled = !inProgress
        binding.loginStatus.isVisible = inProgress
        if (inProgress) binding.loginStatus.setText(R.string.login_in_progress)
    }

    private fun showError(messageRes: Int) {
        binding.btnKakaoLogin.isEnabled = true
        binding.loginStatus.setText(messageRes)
        binding.loginStatus.isVisible = true
    }

    private fun proceedToOnboarding() {
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val TAG = "GuideSvc"
    }
}
