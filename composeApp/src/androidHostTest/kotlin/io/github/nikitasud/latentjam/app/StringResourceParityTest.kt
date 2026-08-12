/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Element

/**
 * The translations, pinned against the source strings.
 *
 * Every failure this catches is invisible at runtime: a key added to values/ and forgotten in a
 * translation silently falls back to English, a dropped %1$d silently formats the wrong argument,
 * and a \' silently reaches the UI as a backslash. None of them break the build, and none of them
 * are visible unless you happen to run the app in that language.
 *
 * This lives in androidHostTest rather than commonTest because it reads the .xml files off disk,
 * and common Kotlin has no filesystem.
 */
class StringResourceParityTest {

    @Test
    fun `every source key is translated in every locale, with no orphans`() {
        val source = bundles.getValue(SOURCE)
        val problems = mutableListOf<String>()
        for (locale in LOCALES) {
            val translation = bundles.getValue(locale)
            (source.keys - translation.keys).sorted().forEach {
                problems += "$locale is missing '$it'"
            }
            (translation.keys - source.keys).sorted().forEach {
                problems += "$locale has orphan '$it' (not in values/)"
            }
        }
        assertNoProblems(problems, "Key parity broke")
    }

    @Test
    fun `positional placeholders survive translation`() {
        val source = bundles.getValue(SOURCE)
        val problems = mutableListOf<String>()
        for (locale in LOCALES) {
            for ((key, translated) in bundles.getValue(locale).entries) {
                val expected = source.entries[key]?.placeholders ?: continue
                // Order is deliberately not checked — grammar reorders arguments, and that is
                // exactly what positional placeholders are for. Presence is what matters.
                //
                // Every item of a plural is checked against the whole plural's placeholders,
                // not just its own category: languages with four plural forms are where a
                // placeholder actually goes missing, and comparing only the union would let a
                // dropped %1$d in the Russian 'many' form through.
                translated.values.forEach { value ->
                    val got = value.placeholderIndices()
                    if (got != expected) {
                        problems += "$locale/$key expected ${expected.pretty()} but has ${got.pretty()}"
                    }
                }
            }
        }
        assertNoProblems(problems, "Placeholders were lost in translation")
    }

    @Test
    fun `no string escapes its apostrophes`() {
        // Compose Multiplatform unescapes only \uXXXX, \n, \t and \\ — a \' is passed through
        // verbatim and renders as a literal backslash. values/strings.xml is checked too: its own
        // rules comment opens "Rules for this file and every translation of it".
        val problems = mutableListOf<String>()
        for (bundle in listOf(SOURCE) + LOCALES) {
            for ((key, entry) in bundles.getValue(bundle).entries) {
                entry.values.filter { it.contains("\\'") }.forEach {
                    problems += "$bundle/$key: ${it.trim()}"
                }
            }
        }
        assertNoProblems(problems, "Escaped apostrophes reach the UI as backslashes")
    }

    @Test
    fun `values-in is a faithful duplicate of values-id`() {
        // Indonesian needs both folders: java.util.Locale reports "in" on API 24-34 and "id" from
        // 35, and Compose MP matches qualifiers by exact string. The files are NOT byte-identical
        // — values-in carries a header comment explaining itself — so compare the bodies.
        val id = body("values-id")
        val about = body("values-in")
        assertEquals(id, about, "values-in/strings.xml drifted from values-id/strings.xml")
    }

    @Test
    fun `every plurals declares the other category`() {
        // "other" is the CLDR fallback and the only category required in all languages.
        val problems = mutableListOf<String>()
        for (bundle in listOf(SOURCE) + LOCALES) {
            for ((key, entry) in bundles.getValue(bundle).entries) {
                if (entry.isPlural && "other" !in entry.categories) {
                    problems += "$bundle/$key declares ${entry.categories.sorted()} but no 'other'"
                }
            }
        }
        assertNoProblems(problems, "Plurals are missing their required fallback")
    }

    @Test
    fun `the test is looking at the files it thinks it is`() {
        // A parity test that silently finds no files would pass forever. Pin the shape instead:
        // a new locale folder has to be registered here, and a deleted one has to be noticed.
        val onDisk = resourcesDir.listFiles { f: File -> f.isDirectory && f.name.startsWith("values-") }
            .orEmpty()
            .map { it.name }
            .toSortedSet()
        assertEquals(
            LOCALES.toSortedSet(),
            onDisk,
            "Locale folders on disk no longer match the list this test checks",
        )
        assertTrue(bundles.getValue(SOURCE).entries.size > 100, "values/strings.xml parsed suspiciously empty")
    }

    // ----------------------------------------------------------------- parsing

    private data class Entry(
        val values: List<String>,
        val categories: Set<String>,
        val isPlural: Boolean,
    ) {
        /** Union across a plural's categories: the set of argument indices the string consumes. */
        val placeholders: Set<Int> = values.flatMap { it.placeholderIndices() }.toSet()
    }

    private class Bundle(val entries: Map<String, Entry>) {
        val keys: Set<String> get() = entries.keys
    }

    private companion object {
        const val SOURCE = "values"

        val LOCALES = listOf(
            "values-ru", "values-ro", "values-es", "values-pt-rBR", "values-de", "values-fr",
            "values-it", "values-zh-rCN", "values-ja", "values-ko", "values-tr", "values-uk",
            "values-pl", "values-id", "values-in", "values-ar", "values-hi",
        )

        val PLACEHOLDER = Regex("%(\\d+)\\$")

        val resourcesDir: File by lazy {
            // Gradle runs host tests with the module directory as the working directory, but do
            // not rely on it — walk up until the resources show up, and say so loudly if they
            // never do.
            var dir: File? = File(
                requireNotNull(System.getProperty("user.dir")) {
                    "The test process has no user.dir"
                },
            ).absoluteFile
            while (dir != null) {
                File(dir, "src/commonMain/composeResources")
                    .takeIf { it.isDirectory }
                    ?.let { return@lazy it }
                File(dir, "composeApp/src/commonMain/composeResources")
                    .takeIf { it.isDirectory }
                    ?.let { return@lazy it }
                dir = dir.parentFile
            }
            fail("Could not find composeResources from ${System.getProperty("user.dir")}")
        }

        val bundles: Map<String, Bundle> by lazy {
            (listOf(SOURCE) + LOCALES).associateWith { parse(it) }
        }

        fun String.placeholderIndices(): Set<Int> =
            PLACEHOLDER.findAll(this).map { it.groupValues[1].toInt() }.toSet()

        fun Set<Int>.pretty(): String =
            if (isEmpty()) "no placeholders" else sorted().joinToString(", ") { "%$it\$" }

        fun file(bundle: String): File = File(resourcesDir, "$bundle/strings.xml")

        /** The `<resources>` element onward, so a differing header comment is not a difference. */
        fun body(bundle: String): String {
            val text = file(bundle).readText()
            val start = text.indexOf("<resources>")
            assertTrue(start >= 0, "${file(bundle)} has no <resources> element")
            return text.substring(start)
        }

        fun parse(bundle: String): Bundle {
            val source = file(bundle)
            assertTrue(source.isFile, "Missing $source")
            val document = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(source)
            val entries = mutableMapOf<String, Entry>()

            document.getElementsByTagName("string").elements().forEach {
                entries[it.getAttribute("name")] =
                    Entry(listOf(it.textContent), emptySet(), isPlural = false)
            }
            document.getElementsByTagName("plurals").elements().forEach { plurals ->
                val items = plurals.getElementsByTagName("item").elements()
                entries[plurals.getAttribute("name")] = Entry(
                    values = items.map { it.textContent },
                    categories = items.map { it.getAttribute("quantity") }.toSet(),
                    isPlural = true,
                )
            }
            return Bundle(entries)
        }

        fun org.w3c.dom.NodeList.elements(): List<Element> =
            (0 until length).map { item(it) as Element }

        fun assertNoProblems(problems: List<String>, headline: String) {
            if (problems.isEmpty()) return
            fail("$headline — ${problems.size} problem(s):\n" + problems.joinToString("\n") { "  - $it" })
        }
    }
}
