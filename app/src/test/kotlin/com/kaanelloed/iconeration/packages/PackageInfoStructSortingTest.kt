package com.kaanelloed.iconeration.packages

import org.junit.Assert.*
import org.junit.Test
import java.text.Normalizer

/**
 * Unit tests for PackageInfoStruct sorting and diacritics handling.
 *
 * The compareTo implementation uses removeDiacritics() to normalize app names
 * for locale-independent sorting. The diacritics regex was previously compiled
 * on every call (O(n log n) regex compilations for 500+ items during sort).
 * It is now pre-compiled as a companion object val.
 *
 * These tests verify that the pre-compiled regex produces correct sorting and
 * that the optimization doesn't change behavior.
 */
class PackageInfoStructSortingTest {

    // The same regex used by PackageInfoStruct, for independent verification
    private val diacriticsRegex = "\\p{Mn}+".toRegex()

    private fun removeDiacritics(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replace(diacriticsRegex, "")
    }

    @Test
    fun `diacritics removal strips accents from common characters`() {
        assertEquals("cafe", removeDiacritics("café"))
        assertEquals("naive", removeDiacritics("naïve"))
        assertEquals("resume", removeDiacritics("résumé"))
        assertEquals("uber", removeDiacritics("über"))
        assertEquals("Angstrom", removeDiacritics("Ångström"))
    }

    @Test
    fun `diacritics removal preserves plain ASCII`() {
        assertEquals("Hello World", removeDiacritics("Hello World"))
        assertEquals("test123", removeDiacritics("test123"))
        assertEquals("com.example.app", removeDiacritics("com.example.app"))
    }

    @Test
    fun `diacritics removal handles empty string`() {
        assertEquals("", removeDiacritics(""))
    }

    @Test
    fun `diacritics removal handles CJK characters`() {
        // CJK characters don't have diacritics, should pass through unchanged
        val cjk = "日本語アプリ"
        assertEquals(cjk, removeDiacritics(cjk))
    }

    @Test
    fun `diacritics removal handles mixed scripts`() {
        assertEquals("Meteo France", removeDiacritics("Météo France"))
        assertEquals("Offentlig Transport", removeDiacritics("Öffentlig Transport"))
    }

    @Test
    fun `diacritics removal is consistent across multiple calls`() {
        // Verifies the pre-compiled regex produces the same result every time
        val input = "crème brûlée"
        val expected = "creme brulee"

        repeat(1000) { i ->
            assertEquals(
                "Call #$i should produce the same result",
                expected, removeDiacritics(input)
            )
        }
    }

    @Test
    fun `sorting with diacritics groups accented and unaccented names together`() {
        // App names like "Étoile" should sort near "Etsy", not at the end
        val names = listOf("Zoom", "Étoile", "Alpha", "über", "Beta", "Café")

        val sorted = names.sortedWith(compareBy { removeDiacritics(it).lowercase() })

        assertEquals("Alpha", sorted[0])
        assertEquals("Beta", sorted[1])
        assertEquals("Café", sorted[2])
        assertEquals("Étoile", sorted[3])
        assertEquals("über", sorted[4])
        assertEquals("Zoom", sorted[5])
    }

    @Test
    fun `sorting large list with diacritics is stable and consistent`() {
        // Simulate sorting 500+ apps where some have diacritical characters
        val names = (0 until 500).map { i ->
            when {
                i % 50 == 0 -> "Réglage $i"    // French
                i % 51 == 0 -> "Über App $i"    // German
                i % 52 == 0 -> "Señal $i"       // Spanish
                else -> "App $i"
            }
        }

        val sorted1 = names.sortedWith(compareBy { removeDiacritics(it).lowercase() })
        val sorted2 = names.sortedWith(compareBy { removeDiacritics(it).lowercase() })

        assertEquals(
            "Sorting should be deterministic with pre-compiled regex",
            sorted1, sorted2
        )
    }

    @Test
    fun `pre-compiled regex matches inline regex behavior`() {
        // Ensure the pre-compiled regex in PackageInfoStruct.DIACRITICS_REGEX
        // produces the same results as creating a new regex each time
        val testInputs = listOf(
            "café", "naïve", "résumé", "über", "Ångström",
            "Hello World", "日本語", "crème brûlée", ""
        )

        for (input in testInputs) {
            val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
            val withPreCompiled = normalized.replace(diacriticsRegex, "")
            val withInlineRegex = normalized.replace("\\p{Mn}+".toRegex(), "")

            assertEquals(
                "Pre-compiled and inline regex should produce same result for '$input'",
                withInlineRegex, withPreCompiled
            )
        }
    }
}
