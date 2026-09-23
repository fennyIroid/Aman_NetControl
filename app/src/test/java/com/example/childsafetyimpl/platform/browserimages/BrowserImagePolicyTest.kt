package com.example.childsafetyimpl.platform.browserimages

import com.example.childsafetyimpl.platform.content.ProtectionLevel
import com.example.childsafetyimpl.platform.content.ValuesCategory
import com.example.childsafetyimpl.platform.content.ValuesCategory.EXPLICIT_IMAGERY
import com.example.childsafetyimpl.platform.content.ValuesCategory.GAMBLING_IMAGERY
import com.example.childsafetyimpl.platform.content.ValuesCategory.SUBSTANCE_IMAGERY
import com.example.childsafetyimpl.platform.content.ValuesCategory.SUGGESTIVE_IMAGERY
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserImagePolicyTest {

    private fun scores(porn: Float = 0f, hentai: Float = 0f, sexy: Float = 0f) = NsfwScores(
        drawings = 0f,
        hentai = hentai,
        neutral = 1f - porn - hentai - sexy,
        porn = porn,
        sexy = sexy,
    )

    private fun policy(level: ProtectionLevel, vararg categories: ValuesCategory) =
        BrowserImagePolicy.from(level, categories.toSet())

    @Test
    fun `the defaults hand image search to this path`() {
        assertThat(BrowserImagePolicy.from(ProtectionLevel.default, ValuesCategory.defaults)).isNotNull()
    }

    @Test
    fun `a category these models cannot see keeps the browser on the capture loop`() {
        // Handing the page over would quietly stop covering gambling or smoking (rule 12).
        assertThat(policy(ProtectionLevel.BALANCED, EXPLICIT_IMAGERY, GAMBLING_IMAGERY)).isNull()
        assertThat(policy(ProtectionLevel.BALANCED, EXPLICIT_IMAGERY, SUBSTANCE_IMAGERY)).isNull()
        assertThat(policy(ProtectionLevel.BALANCED)).isNull()
    }

    @Test
    fun `explicit counts porn and hentai together`() {
        val rules = policy(ProtectionLevel.BALANCED, EXPLICIT_IMAGERY)!!
        assertThat(rules.covers(scores(porn = 0.4f, hentai = 0.35f), drugScore = 0f)).isTrue()
        assertThat(rules.covers(scores(porn = 0.4f), drugScore = 0f)).isFalse()
    }

    @Test
    fun `suggestive counts only when the parent chose it`() {
        val swimwear = scores(sexy = 0.9f)
        assertThat(policy(ProtectionLevel.BALANCED, EXPLICIT_IMAGERY)!!.covers(swimwear, 0f)).isFalse()
        assertThat(policy(ProtectionLevel.BALANCED, EXPLICIT_IMAGERY, SUGGESTIVE_IMAGERY)!!.covers(swimwear, 0f)).isTrue()
    }

    @Test
    fun `stricter levels cover on weaker evidence`() {
        val borderline = scores(porn = 0.6f)
        assertThat(policy(ProtectionLevel.RELAXED, EXPLICIT_IMAGERY)!!.covers(borderline, 0f)).isFalse()
        assertThat(policy(ProtectionLevel.BALANCED, EXPLICIT_IMAGERY)!!.covers(borderline, 0f)).isFalse()
        assertThat(policy(ProtectionLevel.STRICT, EXPLICIT_IMAGERY)!!.covers(borderline, 0f)).isTrue()
    }

    @Test
    fun `drug imagery is part of explicit`() {
        val rules = policy(ProtectionLevel.BALANCED, EXPLICIT_IMAGERY)!!
        assertThat(rules.covers(scores(), drugScore = 0.5f)).isTrue()
        assertThat(rules.covers(scores(), drugScore = 0.2f)).isFalse()
    }

    @Test
    fun `a drug score that was never measured is not clean`() {
        assertThat(policy(ProtectionLevel.BALANCED, EXPLICIT_IMAGERY)!!.covers(scores(), drugScore = null)).isTrue()
    }

    @Test
    fun `with only suggestive chosen drugs are not judged`() {
        val rules = policy(ProtectionLevel.BALANCED, SUGGESTIVE_IMAGERY)!!
        assertThat(rules.drugThreshold).isNull()
        assertThat(rules.covers(scores(), drugScore = null)).isFalse()
        assertThat(rules.covers(scores(porn = 0.9f), drugScore = null)).isFalse()
    }
}
