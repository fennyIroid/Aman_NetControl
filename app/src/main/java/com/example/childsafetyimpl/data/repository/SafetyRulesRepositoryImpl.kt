package com.example.childsafetyimpl.data.repository

import com.example.childsafetyimpl.data.local.AppRuleDao
import com.example.childsafetyimpl.data.local.AppRuleEntity
import com.example.childsafetyimpl.data.local.CategoryBudgetDao
import com.example.childsafetyimpl.data.local.CategoryBudgetEntity
import com.example.childsafetyimpl.data.local.DomainRuleDao
import com.example.childsafetyimpl.data.local.DomainRuleEntity
import com.example.childsafetyimpl.data.local.WebCategoryDao
import com.example.childsafetyimpl.data.local.WebCategorySettingEntity
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.di.IoDispatcher
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.AppRule
import com.example.childsafetyimpl.domain.model.CategoryBudget
import com.example.childsafetyimpl.domain.model.DomainRule
import com.example.childsafetyimpl.domain.model.FilterVerdict
import com.example.childsafetyimpl.domain.model.RuleAction
import com.example.childsafetyimpl.domain.model.RuleSource
import com.example.childsafetyimpl.domain.model.WebCategory
import com.example.childsafetyimpl.domain.repository.SafetyRulesRepository
import com.example.childsafetyimpl.platform.network.SystemTrafficExemptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafetyRulesRepositoryImpl @Inject constructor(
    private val appRuleDao: AppRuleDao,
    private val categoryBudgetDao: CategoryBudgetDao,
    private val domainRuleDao: DomainRuleDao,
    private val webCategoryDao: WebCategoryDao,
    private val preferences: SafetyPreferences,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SafetyRulesRepository {

    /**
     * In-memory snapshot of the rule set.
     *
     * Host evaluation runs on the VPN read loop and on the WebView interception thread, both of
     * which are latency-critical. Hitting the database per lookup would put disk I/O in the path
     * of every DNS query on the device, so the rules are cached and refreshed on write.
     */
    private var cachedRules: List<DomainRuleEntity> = emptyList()
    private var cachedBlockedCategories: Set<WebCategory> = emptySet()
    private var cachedAllowListOnly: Boolean = false
    private var cacheLoaded = false
    private val cacheMutex = Mutex()

    override fun observeAppRules(): Flow<List<AppRule>> =
        appRuleDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun appRules(): List<AppRule> = withContext(ioDispatcher) {
        appRuleDao.getAll().map { it.toDomain() }
    }

    override suspend fun upsertAppRule(rule: AppRule) = withContext(ioDispatcher) {
        appRuleDao.upsert(
            AppRuleEntity(
                packageName = rule.packageName,
                appLabel = rule.appLabel,
                dailyLimitMinutes = rule.dailyLimitMinutes,
                blocked = rule.blocked,
                blockFromMinuteOfDay = rule.blockFromMinuteOfDay,
                blockToMinuteOfDay = rule.blockToMinuteOfDay,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun deleteAppRule(packageName: String) = withContext(ioDispatcher) {
        appRuleDao.delete(packageName)
    }

    override fun observeCategoryBudgets(): Flow<List<CategoryBudget>> =
        categoryBudgetDao.observeAll().map { rows ->
            rows.map { CategoryBudget(it.category, it.dailyLimitMinutes, it.enabled) }
        }

    override suspend fun setCategoryBudget(
        category: AppCategory,
        minutes: Int?,
        enabled: Boolean,
    ) = withContext(ioDispatcher) {
        categoryBudgetDao.upsert(CategoryBudgetEntity(category, minutes, enabled))
    }

    override fun observeDomainRules(): Flow<List<DomainRule>> =
        domainRuleDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeParentDomainRules(): Flow<List<DomainRule>> =
        domainRuleDao.observeParentRules().map { rows -> rows.map { it.toDomain() } }

    override suspend fun addParentDomainRule(pattern: String, block: Boolean) =
        withContext(ioDispatcher) {
            val normalised = normaliseHost(pattern)
            if (normalised.isBlank()) return@withContext
            domainRuleDao.upsert(
                DomainRuleEntity(
                    pattern = normalised,
                    category = WebCategory.UNCATEGORISED,
                    action = if (block) RuleAction.BLOCK else RuleAction.ALLOW,
                    source = RuleSource.PARENT,
                    enabled = true,
                )
            )
            refreshFilterCache()
        }

    override suspend fun deleteDomainRule(id: Long) = withContext(ioDispatcher) {
        domainRuleDao.delete(id)
        refreshFilterCache()
    }

    override fun observeWebCategories(): Flow<Map<WebCategory, Boolean>> =
        webCategoryDao.observeAll().map { rows -> rows.associate { it.category to it.blocked } }

    override suspend fun setWebCategoryBlocked(category: WebCategory, blocked: Boolean) =
        withContext(ioDispatcher) {
            webCategoryDao.upsert(WebCategorySettingEntity(category, blocked))
            refreshFilterCache()
        }

    override suspend fun refreshFilterCache() = cacheMutex.withLock {
        withContext(ioDispatcher) {
            cachedRules = domainRuleDao.getEnabled()
            cachedBlockedCategories = webCategoryDao.getBlocked().map { it.category }.toSet()
            cachedAllowListOnly = preferences.settings.first().allowListOnlyBrowsing
            cacheLoaded = true
        }
    }

    /**
     * Host evaluation, in the order that produces the least surprising outcome.
     *
     * An explicit parent allow beats everything, so a parent can always carve out an exception to
     * a category. An explicit block comes next. Category rules apply only after both, and
     * allow-list-only mode is checked last because it is the most restrictive posture and should
     * not silently override a rule the parent set by hand.
     */
    override suspend fun evaluateHost(host: String): FilterVerdict {
        if (!cacheLoaded) refreshFilterCache()

        val normalised = normaliseHost(host)
        if (normalised.isBlank()) {
            return FilterVerdict(host, true, null, null, "Empty host, nothing to evaluate.")
        }

        val matches = cachedRules.filter { hostMatches(normalised, it.pattern) }

        matches.firstOrNull { it.action == RuleAction.ALLOW && it.source == RuleSource.PARENT }
            ?.let {
                return FilterVerdict(
                    host = normalised,
                    allowed = true,
                    matchedPattern = it.pattern,
                    category = it.category,
                    reason = "Allowed explicitly by the parent.",
                )
            }

        matches.firstOrNull { it.action == RuleAction.BLOCK && it.source == RuleSource.PARENT }
            ?.let {
                return FilterVerdict(
                    host = normalised,
                    allowed = false,
                    matchedPattern = it.pattern,
                    category = it.category,
                    reason = "Blocked explicitly by the parent.",
                )
            }

        matches.firstOrNull { it.action == RuleAction.BLOCK && it.category in cachedBlockedCategories }
            ?.let {
                return FilterVerdict(
                    host = normalised,
                    allowed = false,
                    matchedPattern = it.pattern,
                    category = it.category,
                    reason = "The ${it.category.label} category is switched on.",
                )
            }

        // Unlisted, but plumbing the phone needs to keep working. Checked only here, after the
        // parent's own rules and the categories, so it can never undo a block they chose.
        if (cachedAllowListOnly && SystemTrafficExemptions.isExempt(normalised)) {
            return FilterVerdict(
                host = normalised,
                allowed = true,
                matchedPattern = null,
                category = null,
                reason = "A system service the phone needs, kept reachable in allow-list mode.",
            )
        }

        if (cachedAllowListOnly) {
            return FilterVerdict(
                host = normalised,
                allowed = false,
                matchedPattern = null,
                category = WebCategory.UNCATEGORISED,
                reason = "Allow-list-only browsing is on and this site is not on the list.",
            )
        }

        return FilterVerdict(
            host = normalised,
            allowed = true,
            matchedPattern = matches.firstOrNull()?.pattern,
            category = matches.firstOrNull()?.category,
            reason = if (matches.isEmpty()) "No rule matched this host."
            else "Matched a rule whose category is not currently blocked.",
        )
    }

    /**
     * Suffix matching on label boundaries.
     *
     * A plain contains check would block "notpornhub-safety.org" along with the real thing, and a
     * plain equals check would miss every subdomain. Matching on the dot boundary is what makes a
     * single entry cover a site without spilling onto unrelated names.
     */
    private fun hostMatches(host: String, pattern: String): Boolean =
        host == pattern || host.endsWith(".$pattern")

    private fun normaliseHost(raw: String): String = raw
        .trim()
        .lowercase()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .substringBefore(':')
        .removePrefix("www.")
        .trimEnd('.')
}

private fun AppRuleEntity.toDomain() = AppRule(
    packageName = packageName,
    appLabel = appLabel,
    dailyLimitMinutes = dailyLimitMinutes,
    blocked = blocked,
    blockFromMinuteOfDay = blockFromMinuteOfDay,
    blockToMinuteOfDay = blockToMinuteOfDay,
)

private fun DomainRuleEntity.toDomain() = DomainRule(
    id = id,
    pattern = pattern,
    category = category,
    action = action,
    source = source,
    enabled = enabled,
)
