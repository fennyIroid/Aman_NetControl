package com.example.childsafetyimpl.data

import com.example.childsafetyimpl.data.local.AppRuleDao
import com.example.childsafetyimpl.data.local.AppRuleEntity
import com.example.childsafetyimpl.data.local.CategoryBudgetDao
import com.example.childsafetyimpl.data.local.CategoryBudgetEntity
import com.example.childsafetyimpl.data.local.DomainRuleDao
import com.example.childsafetyimpl.data.local.DomainRuleEntity
import com.example.childsafetyimpl.data.local.WebCategoryDao
import com.example.childsafetyimpl.data.local.WebCategorySettingEntity
import com.example.childsafetyimpl.data.prefs.SafetyPreferences
import com.example.childsafetyimpl.data.repository.SafetyRulesRepositoryImpl
import com.example.childsafetyimpl.domain.model.AppCategory
import com.example.childsafetyimpl.domain.model.RuleAction
import com.example.childsafetyimpl.domain.model.RuleSource
import com.example.childsafetyimpl.domain.model.WebCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host evaluation runs on the VPN read loop and on the WebView interception thread, and it decides
 * what a child can reach. The two failure modes it must avoid are under-blocking a subdomain and
 * over-blocking an unrelated name that merely contains a blocked string.
 */
class HostFilterTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun repository(
        rules: List<DomainRuleEntity>,
        blockedCategories: Set<WebCategory> = setOf(WebCategory.ADULT, WebCategory.GAMBLING),
        allowListOnly: Boolean = false,
    ): SafetyRulesRepositoryImpl = SafetyRulesRepositoryImpl(
        appRuleDao = FakeAppRuleDao(),
        categoryBudgetDao = FakeCategoryBudgetDao(),
        domainRuleDao = FakeDomainRuleDao(rules),
        webCategoryDao = FakeWebCategoryDao(blockedCategories),
        preferences = FakePreferences(allowListOnly),
        ioDispatcher = dispatcher,
    )

    private fun blockRule(pattern: String, category: WebCategory) = DomainRuleEntity(
        id = pattern.hashCode().toLong(),
        pattern = pattern,
        category = category,
        action = RuleAction.BLOCK,
        source = RuleSource.SEEDED_LIST,
        enabled = true,
    )

    @Test
    fun `blocks an exact host in a blocked category`() = runTest(dispatcher) {
        val verdict = repository(listOf(blockRule("badsite.com", WebCategory.ADULT)))
            .evaluateHost("badsite.com")
        assertFalse(verdict.allowed)
        assertEquals(WebCategory.ADULT, verdict.category)
    }

    @Test
    fun `blocks a subdomain of a blocked host`() = runTest(dispatcher) {
        val verdict = repository(listOf(blockRule("badsite.com", WebCategory.ADULT)))
            .evaluateHost("cdn.images.badsite.com")
        assertFalse(verdict.allowed)
    }

    /**
     * The reason matching happens on the dot boundary. A plain "contains" check would block this,
     * which is the classic over-blocking bug that erodes a parent's trust in the whole product.
     */
    @Test
    fun `does not block an unrelated host that merely contains the pattern`() = runTest(dispatcher) {
        val repository = repository(listOf(blockRule("badsite.com", WebCategory.ADULT)))
        assertTrue(repository.evaluateHost("notbadsite.com").allowed)
        assertTrue(repository.evaluateHost("badsite.com.example.org").allowed)
    }

    @Test
    fun `allows a host in a category that is switched off`() = runTest(dispatcher) {
        val verdict = repository(
            rules = listOf(blockRule("social.example", WebCategory.SOCIAL)),
            blockedCategories = setOf(WebCategory.ADULT),
        ).evaluateHost("social.example")
        assertTrue(verdict.allowed)
    }

    @Test
    fun `a parent allow beats a category block`() = runTest(dispatcher) {
        val rules = listOf(
            blockRule("streamer.example", WebCategory.STREAMING),
            DomainRuleEntity(
                id = 99,
                pattern = "streamer.example",
                category = WebCategory.UNCATEGORISED,
                action = RuleAction.ALLOW,
                source = RuleSource.PARENT,
                enabled = true,
            ),
        )
        val verdict = repository(rules, blockedCategories = setOf(WebCategory.STREAMING))
            .evaluateHost("streamer.example")
        assertTrue(verdict.allowed)
        assertEquals("Allowed explicitly by the parent.", verdict.reason)
    }

    @Test
    fun `a parent block applies even when no category is switched on`() = runTest(dispatcher) {
        val rules = listOf(
            DomainRuleEntity(
                id = 5,
                pattern = "distracting.example",
                category = WebCategory.UNCATEGORISED,
                action = RuleAction.BLOCK,
                source = RuleSource.PARENT,
                enabled = true,
            ),
        )
        val verdict = repository(rules, blockedCategories = emptySet())
            .evaluateHost("distracting.example")
        assertFalse(verdict.allowed)
    }

    @Test
    fun `allow-list-only mode blocks anything not explicitly allowed`() = runTest(dispatcher) {
        val repository = repository(
            rules = listOf(
                DomainRuleEntity(
                    id = 7,
                    pattern = "school.example",
                    category = WebCategory.UNCATEGORISED,
                    action = RuleAction.ALLOW,
                    source = RuleSource.PARENT,
                    enabled = true,
                ),
            ),
            allowListOnly = true,
        )
        assertTrue(repository.evaluateHost("school.example").allowed)
        assertFalse(repository.evaluateHost("anythingelse.example").allowed)
    }

    /**
     * Allow-list-only refuses everything unlisted, and a parent lists websites. Without the
     * system exemptions the phone stops updating apps, receiving notifications and believing it
     * has internet at all, which is how a strict filter gets switched off by the evening.
     */
    @Test
    fun `allow-list-only mode keeps the phone's own plumbing reachable`() = runTest(dispatcher) {
        val repository = repository(emptyList(), allowListOnly = true)
        listOf(
            "connectivitycheck.gstatic.com",
            "mtalk.google.com",
            "play.googleapis.com",
            "r3---sn-abc.gvt1.com",
            "time.android.com",
        ).forEach { host ->
            assertTrue("$host must stay reachable", repository.evaluateHost(host).allowed)
        }
        assertFalse(repository.evaluateHost("youtube.com").allowed)
        assertFalse(repository.evaluateHost("google.com").allowed)
    }

    @Test
    fun `a system exemption never overrides a block the parent chose`() = runTest(dispatcher) {
        val repository = repository(
            rules = listOf(
                DomainRuleEntity(
                    id = 8,
                    pattern = "gvt1.com",
                    category = WebCategory.UNCATEGORISED,
                    action = RuleAction.BLOCK,
                    source = RuleSource.PARENT,
                    enabled = true,
                ),
            ),
            allowListOnly = true,
        )
        assertFalse(repository.evaluateHost("gvt1.com").allowed)
    }

    @Test
    fun `system exemptions do nothing outside allow-list-only mode`() = runTest(dispatcher) {
        // Outside that mode an unlisted host is already allowed; the exemption must not become a
        // second allow path that outranks a category block.
        val repository = repository(listOf(blockRule("mtalk.google.com", WebCategory.ADULT)))
        assertFalse(repository.evaluateHost("mtalk.google.com").allowed)
    }

    @Test
    fun `normalises scheme, path, port, case and a leading www`() = runTest(dispatcher) {
        val repository = repository(listOf(blockRule("badsite.com", WebCategory.ADULT)))
        listOf(
            "https://www.BadSite.com/some/path?q=1",
            "http://badsite.com:8080",
            "BADSITE.COM.",
            "www.badsite.com",
        ).forEach { input ->
            assertFalse("Should have blocked $input", repository.evaluateHost(input).allowed)
        }
    }

    @Test
    fun `an empty host is allowed rather than crashing`() = runTest(dispatcher) {
        assertTrue(repository(emptyList()).evaluateHost("").allowed)
    }
}

// --- fakes -------------------------------------------------------------------------------------

private class FakeDomainRuleDao(private val rules: List<DomainRuleEntity>) : DomainRuleDao {
    override suspend fun insertAll(rules: List<DomainRuleEntity>) = Unit
    override suspend fun upsert(rule: DomainRuleEntity) = Unit
    override suspend fun getEnabled(): List<DomainRuleEntity> = rules.filter { it.enabled }
    override fun observeAll(): Flow<List<DomainRuleEntity>> = flowOf(rules)
    override fun observeParentRules(): Flow<List<DomainRuleEntity>> =
        flowOf(rules.filter { it.source == RuleSource.PARENT })
    override suspend fun count(): Int = rules.size
    override suspend fun delete(id: Long) = Unit
}

private class FakeWebCategoryDao(private val blocked: Set<WebCategory>) : WebCategoryDao {
    override suspend fun upsert(setting: WebCategorySettingEntity) = Unit
    override suspend fun insertAll(settings: List<WebCategorySettingEntity>) = Unit
    override fun observeAll(): Flow<List<WebCategorySettingEntity>> =
        flowOf(blocked.map { WebCategorySettingEntity(it, true) })
    override suspend fun getBlocked(): List<WebCategorySettingEntity> =
        blocked.map { WebCategorySettingEntity(it, true) }
    override suspend fun get(category: WebCategory): WebCategorySettingEntity? =
        if (category in blocked) WebCategorySettingEntity(category, true) else null
}

private class FakeAppRuleDao : AppRuleDao {
    override suspend fun upsert(rule: AppRuleEntity) = Unit
    override fun observeAll(): Flow<List<AppRuleEntity>> = flowOf(emptyList())
    override suspend fun getAll(): List<AppRuleEntity> = emptyList()
    override suspend fun get(packageName: String): AppRuleEntity? = null
    override suspend fun delete(packageName: String) = Unit
}

private class FakeCategoryBudgetDao : CategoryBudgetDao {
    override suspend fun upsert(budget: CategoryBudgetEntity) = Unit
    override fun observeAll(): Flow<List<CategoryBudgetEntity>> = flowOf(emptyList())
    override suspend fun getEnabled(): List<CategoryBudgetEntity> = emptyList()
    override suspend fun get(category: AppCategory): CategoryBudgetEntity? = null
}

/**
 * The repository only reads the allow-list-only flag from preferences, so the fake supplies a
 * DataStore that returns a fixed value rather than pulling in a real one.
 */
private class FakePreferences(allowListOnly: Boolean) : SafetyPreferences(FakeContext(allowListOnly)) {
    private val fixed = com.example.childsafetyimpl.data.prefs.SafetySettings(
        allowListOnlyBrowsing = allowListOnly,
    )
    override val settings: Flow<com.example.childsafetyimpl.data.prefs.SafetySettings> = flowOf(fixed)
}

private class FakeContext(@Suppress("unused") val allowListOnly: Boolean) :
    android.content.ContextWrapper(null)
