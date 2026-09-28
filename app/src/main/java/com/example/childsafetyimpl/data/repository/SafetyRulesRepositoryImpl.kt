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
import com.example.childsafetyimpl.data.source.HostListSource
import com.example.childsafetyimpl.data.source.HostSet
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
import com.example.childsafetyimpl.platform.network.AdultHostKeywords
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
    private val hostLists: HostListSource,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SafetyRulesRepository {

    /**
     * In-memory snapshot of the rule set.
     *
     * Host evaluation runs on the VPN read loop and on the WebView interception thread, both of
     * which are latency-critical. Hitting the database per lookup would put disk I/O in the path
     * of every DNS query on the device, so the rules are cached and refreshed on write.
     *
     * The snapshot is replaced whole rather than field by field, so a lookup on another thread
     * never sees new rules paired with old categories.
     */
    @Volatile private var snapshot: FilterSnapshot? = null
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
            val rules = domainRuleDao.getEnabled()
            snapshot = FilterSnapshot(
                rulesByPattern = rules.withIndex().groupBy { it.value.pattern },
                blockedCategories = webCategoryDao.getBlocked().map { it.category }.toSet(),
                allowListOnly = preferences.settings.first().allowListOnlyBrowsing,
                // Read from assets once per process; later refreshes reuse the parsed lists.
                hostLists = snapshot?.hostLists ?: hostLists.load(),
            )
        }
    }

    /**
     * Host evaluation, in the order that produces the least surprising outcome.
     *
     * An explicit parent allow beats everything, so a parent can always carve out an exception to
     * a category. An explicit block comes next. Category rules apply only after both, and
     * allow-list-only mode is checked last because it is the most restrictive posture and should
     * not silently override a rule the parent set by hand. The bundled lists count as category
     * rules, after the ones in the database.
     */
    override suspend fun evaluateHost(host: String): FilterVerdict {
        val cache = snapshot ?: run { refreshFilterCache(); snapshot!! }

        val normalised = normaliseHost(host)
        if (normalised.isBlank()) {
            return FilterVerdict(host, true, null, null, "Empty host, nothing to evaluate.")
        }

        val matches = cache.rulesMatching(normalised)

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

        matches.firstOrNull { it.action == RuleAction.BLOCK && it.category in cache.blockedCategories }
            ?.let {
                return FilterVerdict(
                    host = normalised,
                    allowed = false,
                    matchedPattern = it.pattern,
                    category = it.category,
                    reason = "The ${it.category.label} category is switched on.",
                )
            }

        cache.bundledBlock(normalised)?.let { return it }

        // Unlisted, but plumbing the phone needs to keep working. Checked only here, after the
        // parent's own rules and the categories, so it can never undo a block they chose.
        if (cache.allowListOnly && SystemTrafficExemptions.isExempt(normalised)) {
            return FilterVerdict(
                host = normalised,
                allowed = true,
                matchedPattern = null,
                category = null,
                reason = "A system service the phone needs, kept reachable in allow-list mode.",
            )
        }

        if (cache.allowListOnly) {
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
     * The bundled lists and adult-name keywords, for the categories the parent has switched on.
     *
     * A host the phone itself depends on is never refused from here. A parent's own block can
     * still reach it, as before; a list nobody on this device wrote cannot.
     */
    private fun FilterSnapshot.bundledBlock(host: String): FilterVerdict? {
        if (SystemTrafficExemptions.isExempt(host)) return null
        for (category in blockedCategories) {
            val entry = hostLists[category]?.matchOf(host) ?: continue
            return FilterVerdict(
                host = host,
                allowed = false,
                matchedPattern = entry,
                category = category,
                reason = "The ${category.label} category is switched on.",
            )
        }
        if (WebCategory.ADULT in blockedCategories && AdultHostKeywords.matchOf(host) != null) {
            return FilterVerdict(
                host = host,
                allowed = false,
                matchedPattern = null,
                category = WebCategory.ADULT,
                reason = "The ${WebCategory.ADULT.label} category is switched on, and this " +
                    "site's name is an adult one.",
            )
        }
        return null
    }

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

private class FilterSnapshot(
    val rulesByPattern: Map<String, List<IndexedValue<DomainRuleEntity>>>,
    val blockedCategories: Set<WebCategory>,
    val allowListOnly: Boolean,
    val hostLists: Map<WebCategory, HostSet>,
) {
    /**
     * Suffix matching on label boundaries, in the order the rules were stored.
     *
     * A plain contains check would block "notpornhub-safety.org" along with the real thing, and a
     * plain equals check would miss every subdomain. Matching on the dot boundary is what makes a
     * single entry cover a site without spilling onto unrelated names. Looking up the host and
     * each suffix after a dot finds exactly the rules `host == p || host.endsWith(".$p")` would,
     * without testing every rule on every lookup.
     */
    fun rulesMatching(host: String): List<DomainRuleEntity> {
        if (rulesByPattern.isEmpty()) return emptyList()
        val found = mutableListOf<IndexedValue<DomainRuleEntity>>()
        var start = 0
        while (true) {
            rulesByPattern[host.substring(start)]?.let(found::addAll)
            val dot = host.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
        }
        return if (found.size < 2) found.map { it.value } else found.sortedBy { it.index }.map { it.value }
    }
}
