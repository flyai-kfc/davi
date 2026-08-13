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
        /** 잠긴 profile이나 부분 조회 실패가 없을 때만 not_found를 확정할 수 있다. */
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
 * 설치·삭제 callback마다 전체 launcher index를 작게 다시 만든다. 최대 수십~수백 건이고 원본을
 * 영속화하지 않아 증분 캐시의 누락·profile 혼합보다 안전하다.
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
    private var started = false
    private var snapshot = LaunchableAppSnapshot(emptyList(), complete = false, revision = 0)

    @Synchronized
    fun start() {
        if (started) return
        started = true
        source.register(::refresh)
        refresh()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        source.unregister()
        snapshot = LaunchableAppSnapshot(emptyList(), complete = false, revision = snapshot.revision + 1)
    }

    @Synchronized
    fun current(): LaunchableAppSnapshot = snapshot.copy(records = snapshot.records.toList())

    @Synchronized
    private fun refresh() {
        if (!started) return
        snapshot = when (val query = runCatching { source.query() }.getOrDefault(LaunchableAppQuery.Unavailable)) {
            is LaunchableAppQuery.Available -> LaunchableAppSnapshot(
                records = query.records.distinctBy {
                    Triple(it.profileId, it.packageName, it.componentName)
                },
                complete = query.complete,
                revision = snapshot.revision + 1,
            )
            LaunchableAppQuery.Unavailable -> LaunchableAppSnapshot(
                records = emptyList(),
                complete = false,
                revision = snapshot.revision + 1,
            )
        }
        onRefreshed(snapshot)
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
            if (!users.isUserUnlocked(profile)) {
                complete = false
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
        if (records.distinctBy { Triple(it.profileId, it.packageName, it.componentName) }.size == 1) {
            AppCandidateAvailability.LAUNCHABLE
        } else {
            AppCandidateAvailability.AMBIGUOUS
        }
}

internal fun normalizeLaunchableLabel(value: String): String {
    var normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
    while (true) {
        val stripped = LAUNCHER_SUFFIX.replace(normalized, "").trim()
        if (stripped == normalized) break
        normalized = stripped
    }
    return normalized.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()
}

private val LAUNCHER_SUFFIX = Regex("""\s*,\s*(?:알림\s*\d+개|폴더)\s*$""")
private val WHITESPACE = Regex("""\s+""")
