package com.davi.helper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 안내가 멈춘 사실을 알리는 단 하나의 통지 수단(#212).
 *
 * **왜 알림인가** — 이 앱이 알릴 일은 우리가 백그라운드일 때만 생기는데(안내 중 사용자는 다른
 * 앱에 있다), 그때 토스트는 시스템이 억제하고([ServiceOffAction.NOTIFY]) 오버레이는 서비스가
 * unbind되는 중이라 함께 죽는다. 남는 것이 알림이고, **알림은 남아 있어 누를 수 있다** —
 * 그 탭이 예전의 자동 복귀를 대신한다(`ServiceOffAction` KDoc의 실측).
 *
 * 채널·id·탭 목적지를 한자리에 두는 이유는 호출부가 "무엇을 알릴지"만 고르게 하기 위해서다.
 */
internal object GuideNotifier {

    /**
     * 알림 **하나를 갱신한다.** 스텝마다 쌓이면 시니어에게 최악이다 — 알림창이 우리 것으로
     * 덮이면 정작 하려던 일에서 더 멀어진다.
     */
    private const val NOTICE_ID = 1

    private const val CHANNEL_ID = "guide_status"

    /**
     * [messageRes]를 알림으로 띄운다. 탭하면 온보딩으로 가고, 그 화면이 **왜 왔는지**를 말한다
     * (`EXTRA_GUIDE_STOPPED` — #190이 복귀용으로 만든 경로를 그대로 물려받는다).
     *
     * 권한이 없으면 조용히 지나간다. `POST_NOTIFICATIONS`는 온보딩이 한 번 묻지만 거부할 수 있고,
     * 거부해도 진행을 막지 않기 때문이다 — 그때는 사용자가 앱을 열 때 `OnboardingActivity`가
     * 잡아낸다.
     */
    fun notifyGuideStopped(context: Context, messageRes: Int) {
        notify(
            context,
            messageRes,
            Intent(context, OnboardingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(OnboardingActivity.EXTRA_GUIDE_STOPPED, true),
        )
    }

    /** 세션이 만료된 동안 백그라운드에 있던 사용자를 로그인 화면으로 돌려보낸다. */
    fun notifyLoginRequired(context: Context) {
        notify(
            context,
            R.string.guide_session_authentication_required,
            Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(LoginActivity.EXTRA_SESSION_AUTHENTICATION_REQUIRED, true),
        )
    }

    private fun notify(context: Context, messageRes: Int, destination: Intent) {
        ensureChannel(context)
        val text = context.getString(messageRes)
        val tap = PendingIntent.getActivity(
            context,
            0,
            destination,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // **임시다.** 앱에 아직 전용 아이콘이 없어(매니페스트도 플랫폼 기본을 쓴다)
            // 단색 도형인 원형 마크를 빌려 쓴다 — 브랜드 로고를 그대로 쓰면 시스템이 흰
            // 실루엣으로 마스킹해 뭉개진다. **이 리소스는 GD-3·GD-4 확인쌍의 ○도 쓴다** —
            // 그쪽을 채운 원이나 체크로 바꾸면 여기도 따라 바뀐다(PR #240 리뷰).
            // 전용 알림 아이콘은 **#232**(앱 레이블·아이콘 확정)에서 함께 정한다.
            .setSmallIcon(R.drawable.ic_davi_circle)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 잠금화면에 그대로 보여도 된다. 이 알림이 싣는 것은 고정 문구뿐이고 **화면 label
            // 인용이 없다** — 숨길 것이 없다. 문구가 화면 내용을 싣게 되면 그때 다시 본다.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTICE_ID, notification) }
            .onFailure {
                // 권한 거부 시 SecurityException. 알릴 수 없는 것은 사고가 아니라 사용자 선택이다.
            }
    }

    /** 안내가 다시 도는 동안 옛 알림을 남겨두지 않는다. */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTICE_ID) }
    }

    /**
     * 채널은 만들어 두기만 하면 되고 중복 생성은 무해하다. 게시 직전에 부르는 이유는 채널이
     * 없으면 알림이 조용히 버려지기 때문이다 — 앱 시작 경로 어딘가에 숨겨 두면 그 경로가 바뀔 때
     * 같이 죽는다.
     *
     * **고중요도다.** 헤드업으로 떠야 다른 앱에 가 있는 사용자가 본다 — 안 보이면 이 기능이
     * 존재할 이유가 없다.
     */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_guide_status),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
