package com.davi.helper

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
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
    val revision: Long,
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
        repeat(MAX_REFRESH_ATTEMPTS) {
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
                    dirty = false
                    refreshed = snapshotCopyLocked()
                }
            }
            refreshed?.let {
                onRefreshed(it)
                return it
            }
        }
        return synchronized(lock) { snapshotCopyLocked() }
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
        users.userProfiles.forEach { profile ->
            // 잠긴 보안 폴더·직장 profile은 지금 안내로 열 수 없으므로 조회 대상에서 제외한다.
            // 안내 가능한 profile의 실제 조회 실패만 complete=false로 보존한다.
            if (!users.isUserUnlocked(profile)) {
                return@forEach
            }
            val activities = runCatching { apps.getActivityList(null, profile) }.getOrElse {
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
    )
}

internal object AppCandidateMatcher {
    fun match(plan: AppCandidatePlan, snapshot: LaunchableAppSnapshot): List<AppCandidateResult> =
        plan.candidates.map { candidate ->
            AppCandidateResult(candidate.candidateId, match(candidate, snapshot))
        }

    private fun match(
        candidate: AppCandidate,
        snapshot: LaunchableAppSnapshot,
    ): AppCandidateAvailability {
        val packageMatches = snapshot.records.filter { it.packageName in candidate.packageNames }
        if (packageMatches.isNotEmpty()) return availabilityOf(packageMatches)

        val aliases = (candidate.labelAliases + candidate.canonicalName)
            .map(::normalizeLaunchableLabel)
            .filter(String::isNotEmpty)
            .toSet()
        val labelMatches = snapshot.records.filter { normalizeLaunchableLabel(it.label) in aliases }
        if (labelMatches.isNotEmpty()) return availabilityOf(labelMatches)
        return if (snapshot.complete) {
            AppCandidateAvailability.NOT_FOUND
        } else {
            AppCandidateAvailability.UNKNOWN
        }
    }

    private fun availabilityOf(records: List<LaunchableAppRecord>): AppCandidateAvailability =
        if (records.distinctBy { it.profileId to it.packageName }.size == 1) {
            AppCandidateAvailability.LAUNCHABLE
        } else {
            AppCandidateAvailability.AMBIGUOUS
        }
}

internal fun normalizeLaunchableLabel(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()
}

private val WHITESPACE = Regex("""\s+""")
