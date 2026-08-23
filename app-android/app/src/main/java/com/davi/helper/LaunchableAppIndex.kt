package com.davi.helper

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Normalizer
import java.util.Locale

/** 기기 안에서만 존재하는 launcher activity. 원문 label·profile은 서버나 상세 로그로 내보내지 않는다. */
internal data class LaunchableAppRecord(
    val packageName: String,
    val componentName: String,
    val label: String,
    val profileId: Int,
    /**
     * 이 record가 **다비가 직접 열 수 있는 프로필**의 것인가(#348).
     *
     * index는 잠기지 않은 프로필을 모두 담으므로 직장·보안 폴더에만 있는 앱도
     * `LAUNCHABLE`이 될 수 있다 — 개인 프로필에 같은 패키지가 있으면 `AMBIGUOUS`로
     * 빠지니 **그 프로필에만 있을 때**가 남는다. 그때 `PackageManager`로는 아이콘도
     * 실행 인텐트도 안 나온다(빈 슬롯 + 열리지 않는 행).
     *
     * **profileId만으로는 판별할 수 없다** — `UserHandle.hashCode()`라 되돌릴 수 없다.
     * 그래서 UserHandle이 아직 손에 있는 조회 시점에 정한다.
     */
    val isPersonalProfile: Boolean,
)

internal sealed interface LaunchableAppQuery {
    data class Available(
        val records: List<LaunchableAppRecord>,
        /** 안내 가능한 profile 조회가 모두 성공했을 때만 not_found를 확정할 수 있다. */
        val complete: Boolean,
    ) : LaunchableAppQuery

    data object Unavailable : LaunchableAppQuery
}

internal interface LaunchableAppSource {
    fun query(): LaunchableAppQuery
    fun register(onChanged: () -> Unit)
    fun unregister()
}

internal data class LaunchableAppSnapshot(
    val records: List<LaunchableAppRecord>,
    val complete: Boolean,
    /** package callback 등으로 index가 무효화된 세대다. records 내용의 identity/version은 아니다. */
    val revision: Long,
    /** false면 조회 중 연속 무효화되어 이 snapshot으로 후보를 판정하면 안 된다. */
    val isStable: Boolean = true,
)

/**
 * 설치·삭제 callback은 index를 무효화만 한다. 실제 전체 조회는 음성 작업 스레드가 다음 snapshot을
 * 요구할 때 수행한다. 원본은 영속화하지 않아 증분 캐시의 누락·profile 혼합보다 안전하다.
 */
internal class LaunchableAppIndex(
    private val source: LaunchableAppSource,
    private val onRefreshed: (LaunchableAppSnapshot) -> Unit = { refreshed ->
        GuideTelemetry.appIndexRefreshed(
            count = refreshed.records.size,
            complete = refreshed.complete,
            revision = refreshed.revision,
        )
    },
) {
    private val lock = Any()
    private var started = false
    private var dirty = false
    private var snapshot = LaunchableAppSnapshot(emptyList(), complete = false, revision = 0)

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            invalidateLocked()
        }
        source.register(::invalidate)
    }

    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            dirty = false
            snapshot = LaunchableAppSnapshot(
                emptyList(),
                complete = false,
                revision = snapshot.revision + 1,
            )
        }
        source.unregister()
    }

    /** 호출 스레드에서 dirty snapshot을 갱신한다. 제품 호출자는 voiceExecutor다. */
    fun current(): LaunchableAppSnapshot {
        repeat(MAX_REFRESH_ATTEMPTS) { attempt ->
            val expectedRevision = synchronized(lock) {
                if (!started || !dirty) return snapshotCopyLocked()
                snapshot.revision
            }
            val query = runCatching { source.query() }
                .getOrDefault(LaunchableAppQuery.Unavailable)
            var refreshed: LaunchableAppSnapshot? = null
            synchronized(lock) {
                if (!started) return snapshotCopyLocked()
                if (snapshot.revision == expectedRevision) {
                    snapshot = snapshotFor(query, expectedRevision)
                    // 일시적 framework/profile 조회 실패는 다음 음성 턴에서 callback 없이도 재시도한다.
                    dirty = !snapshot.complete
                    refreshed = snapshotCopyLocked()
                }
            }
            refreshed?.let {
                onRefreshed(it)
                if (it.complete || attempt == MAX_REFRESH_ATTEMPTS - 1) return it
                // 일시적 framework/profile 실패 때문에 사용자가 같은 목표를 다시 말하지 않도록
                // 같은 음성 턴에서 한 번 더 조회한다. 지속 실패도 이 상한까지만 스윕하며,
                // 잘못된 not_found보다 UNKNOWN을 우선하기 위해 다음 턴에는 다시 시도한다.
            }
        }
        return synchronized(lock) { snapshotCopyLocked().copy(isStable = false) }
    }

    fun revision(): Long = synchronized(lock) { snapshot.revision }

    private fun invalidate() = synchronized(lock) {
        if (started) invalidateLocked()
    }

    private fun invalidateLocked() {
        dirty = true
        snapshot = LaunchableAppSnapshot(
            records = emptyList(),
            complete = false,
            revision = snapshot.revision + 1,
        )
    }

    private fun snapshotFor(
        query: LaunchableAppQuery,
        revision: Long,
    ): LaunchableAppSnapshot = when (query) {
        is LaunchableAppQuery.Available -> LaunchableAppSnapshot(
            records = query.records.distinctBy {
                Triple(it.profileId, it.packageName, it.componentName)
            },
            complete = query.complete,
            revision = revision,
        )
        LaunchableAppQuery.Unavailable -> LaunchableAppSnapshot(
            records = emptyList(),
            complete = false,
            revision = revision,
        )
    }

    private fun snapshotCopyLocked() = snapshot.copy(records = snapshot.records.toList())

    private companion object {
        const val MAX_REFRESH_ATTEMPTS = 2
    }
}

/** Android framework 조회를 위 순수 index 경계에 맞춘다. */
internal class AndroidLaunchableAppSource(context: Context) : LaunchableAppSource {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private var callback: LauncherApps.Callback? = null

    override fun query(): LaunchableAppQuery {
        val apps = launcherApps ?: return LaunchableAppQuery.Unavailable
        val users = userManager ?: return LaunchableAppQuery.Unavailable
        var complete = true
        val records = mutableListOf<LaunchableAppRecord>()
        val visibleProfiles = runCatching { apps.profiles }
            .getOrElse { return LaunchableAppQuery.Unavailable }
        visibleProfiles.forEach { profile ->
            // 잠긴 보안 폴더·직장 profile은 지금 안내로 열 수 없으므로 조회 대상에서 제외한다.
            // LauncherApps가 호출자에게 노출하지 않은 profile은 visibleProfiles에 들어오지 않는다.
            if (!users.isUserUnlocked(profile)) {
                return@forEach
            }
            val activities = try {
                apps.getActivityList(null, profile)
            } catch (_: RuntimeException) {
                // getProfiles()가 노출한 profile의 실패는 실제 부분 조회 실패다.
                // 제외해 not_found로 오판하지 않고 다음 턴 재시도를 위해 불완전하게 남긴다.
                complete = false
                emptyList()
            }
            records += activities.map { it.toRecord(profile) }
        }
        return LaunchableAppQuery.Available(records, complete)
    }

    override fun register(onChanged: () -> Unit) {
        if (callback != null || launcherApps == null) return
        callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) = onChanged()
            override fun onPackageRemoved(packageName: String, user: UserHandle) = onChanged()
            override fun onPackageChanged(packageName: String, user: UserHandle) = onChanged()
            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = onChanged()
            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean,
            ) = onChanged()
        }.also { launcherApps.registerCallback(it, Handler(Looper.getMainLooper())) }
    }

    override fun unregister() {
        callback?.let { launcherApps?.unregisterCallback(it) }
        callback = null
    }

    private fun LauncherActivityInfo.toRecord(profile: UserHandle) = LaunchableAppRecord(
        packageName = applicationInfo.packageName,
        componentName = componentName.flattenToString(),
        label = label?.toString().orEmpty(),
        profileId = profile.hashCode(),
        isPersonalProfile = profile == Process.myUserHandle(),
    )
}

/**
 * 후보 하나의 판정과, **그 판정이 [AppCandidateAvailability.LAUNCHABLE]일 때 좁혀진 앱**.
 *
 * `LAUNCHABLE`은 프로필+패키지가 **정확히 하나**로 좁혀졌을 때만 나온다(둘 이상이면
 * `AMBIGUOUS`). 그래서 [record]는 새로 고른 것이 아니라 **이미 좁혀져 있던 것을 꺼낸
 * 것**이다 — 고르는 규칙이 새로 생기지 않는다. 그 밖의 판정에서는 null이다.
 */
internal data class LaunchableAppMatch(
    val result: AppCandidateResult,
    val record: LaunchableAppRecord?,
)

/**
 * 보기 라벨이 가리키는 설치된 앱(#374 MA-3 아이콘 슬롯). 못 찾으면 null.
 *
 * **서버는 보기를 문자열로만 준다** — 어느 앱인지 알려주지 않는다(`ClarificationQuestion.options`).
 * 그래서 라벨을 기기 index의 앱 이름과 맞춰 본다.
 *
 * **판정 규칙은 [AppCandidateMatcher]와 같은 것을 쓴다** — 정규화한 이름이 **정확히 일치**하고
 * 프로필+패키지가 **하나로 좁혀질 때만** 앱을 돌려준다. 부분 일치나 최선 추정을 하지 않는
 * 것이 핵심이다: 「문자」가 엉뚱한 앱 아이콘으로 뜨면 어르신이 **다른 앱으로 가는 줄 알고**
 * 누른다. **못 찾으면 아이콘을 숨기지, 비슷한 것을 붙이지 않는다.**
 *
 * 「시간 정하기」처럼 앱이 아닌 답은 자연히 null이 된다.
 */
internal fun launchableAppForLabel(
    label: String,
    snapshot: LaunchableAppSnapshot,
): LaunchableAppRecord? {
    val normalized = normalizeLaunchableLabel(label)
    if (normalized.isEmpty()) return null
    val matches = snapshot.records.filter { normalizeLaunchableLabel(it.label) == normalized }
    if (matches.isEmpty()) return null
    val (availability, record) = AppCandidateMatcher.availabilityForRecords(matches)
    if (availability != AppCandidateAvailability.LAUNCHABLE) return null
    return record?.takeIf { it.isPersonalProfile }
}

internal object AppCandidateMatcher {
    fun match(plan: AppCandidatePlan, snapshot: LaunchableAppSnapshot): List<AppCandidateResult> =
        matchDetailed(plan, snapshot).map { it.result }

    /**
     * [match]와 같은 판정에 **좁혀진 앱을 함께** 돌려준다(#348 바로가기 행).
     *
     * `match`를 이것으로 감싸 **판정 경로를 하나로 둔다** — 둘로 갈리면 "행은 뜨는데
     * 사전 점검은 미확인" 같은 어긋남이 조용히 생긴다.
     */
    fun matchDetailed(
        plan: AppCandidatePlan,
        snapshot: LaunchableAppSnapshot,
    ): List<LaunchableAppMatch> = plan.candidates.map { candidate ->
        val (availability, record) = match(candidate, snapshot)
        LaunchableAppMatch(AppCandidateResult(candidate.candidateId, availability), record)
    }

    private fun match(
        candidate: AppCandidate,
        snapshot: LaunchableAppSnapshot,
    ): Pair<AppCandidateAvailability, LaunchableAppRecord?> {
        val packageMatches = snapshot.records.filter { it.packageName in candidate.packageNames }
        if (packageMatches.isNotEmpty()) return availabilityOf(packageMatches)

        val aliases = (candidate.labelAliases + candidate.canonicalName)
            .map(::normalizeLaunchableLabel)
            .filter(String::isNotEmpty)
            .toSet()
        val labelMatches = snapshot.records.filter { normalizeLaunchableLabel(it.label) in aliases }
        if (labelMatches.isNotEmpty()) return availabilityOf(labelMatches)
        return if (snapshot.complete) {
            AppCandidateAvailability.NOT_FOUND to null
        } else {
            AppCandidateAvailability.UNKNOWN to null
        }
    }

    /**
     * 같은 이름으로 걸린 record들에서 **하나로 좁혀졌는지**를 판정한다.
     *
     * [launchableAppForLabel]도 이것을 쓴다 — **판정 규칙을 하나로 두기 위해서다.**
     * 둘로 갈리면 「아이콘은 뜨는데 사전 점검은 미확인」 같은 어긋남이 조용히 생긴다.
     */
    fun availabilityForRecords(
        records: List<LaunchableAppRecord>,
    ): Pair<AppCandidateAvailability, LaunchableAppRecord?> = availabilityOf(records)

    private fun availabilityOf(
        records: List<LaunchableAppRecord>,
    ): Pair<AppCandidateAvailability, LaunchableAppRecord?> =
        if (records.distinctBy { it.profileId to it.packageName }.size == 1) {
            AppCandidateAvailability.LAUNCHABLE to records.first()
        } else {
            AppCandidateAvailability.AMBIGUOUS to null
        }
}

internal fun normalizeLaunchableLabel(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()
}

private val WHITESPACE = Regex("""\s+""")
