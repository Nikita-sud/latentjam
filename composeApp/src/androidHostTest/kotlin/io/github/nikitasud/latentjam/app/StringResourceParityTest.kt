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
    fun `every locale carries every resource file of values`() {
        // values/ is split into strings.xml and a few side files (library_*.xml). A locale that
        // lacks a side file does not fail anything at build time: its keys silently fall back to
        // English. That is how the Folders tab stayed English in 15 languages.
        val sourceFiles = bundles.getValue(SOURCE).files.keys
        val problems = mutableListOf<String>()
        for (locale in LOCALES) {
            val files = bundles.getValue(locale).files.keys
            (sourceFiles - files).sorted().forEach { problems += "$locale is missing $it" }
            (files - sourceFiles).sorted().forEach { problems += "$locale has $it, which values/ does not" }
        }
        assertNoProblems(problems, "Resource files differ from values/")
    }

    @Test
    fun `every source key is translated in every locale, with no orphans`() {
        // Checked file by file: a key that moves between files in a translation still resolves,
        // but the next person to look for it in the matching file will not find it.
        val problems = mutableListOf<String>()
        for ((fileName, source) in bundles.getValue(SOURCE).files) {
            for (locale in LOCALES) {
                val translation = bundles.getValue(locale).files[fileName].orEmpty()
                (source.keys - translation.keys).sorted().forEach {
                    problems += "$locale/$fileName is missing '$it'"
                }
                (translation.keys - source.keys).sorted().forEach {
                    problems += "$locale/$fileName has orphan '$it' (not in values/$fileName)"
                }
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
        // 35, and Compose MP matches qualifiers by exact string.
        assertFaithfulDuplicate(original = "values-id", copy = "values-in")
    }

    @Test
    fun `values-iw is a faithful duplicate of values-he`() {
        // Hebrew is the same story as Indonesian: java.util.Locale reports "iw" on API 24-34 and
        // "he" from 35 (iOS always says "he").
        assertFaithfulDuplicate(original = "values-he", copy = "values-iw")
    }

    @Test
    fun `values-zh is a faithful duplicate of values-zh-rCN`() {
        // Simplified Chinese is the same in CN, SG and MY, but Compose MP matches the region
        // too: without a region-less folder a zh-SG or zh-MY device falls back to English.
        // The two folders hold one language, so they must not drift apart.
        assertFaithfulDuplicate(original = "values-zh-rCN", copy = "values-zh")
    }

    @Test
    fun `Android res folders keep the legacy Indonesian and Hebrew codes`() {
        // The widget, tile, Android Auto and Media3 strings are plain Android resources, which
        // the checks above never read. Android's resource lookup rewrites "id" to "in" and "he"
        // to "iw" before matching (ResourcesImpl.adjustLanguageTag), so a values-id without a
        // values-in is English on every Indonesian device, at every API level.
        val problems = mutableListOf<String>()
        for ((name, root) in androidResourceRoots) {
            assertTrue(root.isDirectory, "$name is gone; update androidResourceRoots")
            for ((original, copy) in LEGACY_CODES) {
                val hasOriginal = File(root, original).isDirectory
                val hasCopy = File(root, copy).isDirectory
                when {
                    hasOriginal && !hasCopy -> problems += "$name has $original but no $copy"
                    hasCopy && !hasOriginal -> problems += "$name has $copy but no $original"
                    hasOriginal -> problems += duplicateProblems(root, original, copy).map { "$name/$it" }
                }
            }
        }
        assertNoProblems(problems, "Android res folders lost a legacy language code")
    }

    @Test
    fun `no translation is left in English`() {
        // A string copied from values/ and never translated passes every other check here: the
        // key exists, the placeholders match. Flag any value that equals the English source,
        // unless the key is a name that reads the same everywhere (SMART, Bluetooth), or that
        // language really writes it the same (German "Album", French "Playlists", Spanish "min").
        // Strings with nothing but placeholders and punctuation are skipped on their own. Both
        // lists are checked the other way too, so they cannot go stale.
        val source = bundles.getValue(SOURCE).entries
        val problems = mutableListOf<String>()
        for (locale in LOCALES) {
            val cognates = SAME_IN_LANGUAGE[locale].orEmpty()
            for ((key, entry) in bundles.getValue(locale).entries) {
                val english = source[key] ?: continue
                val untranslated = entry.values.filter { it in english.values && it.hasWords() }
                val allowed = key in SAME_IN_EVERY_LANGUAGE || key in cognates
                if (untranslated.isNotEmpty() && !allowed) {
                    problems += "$locale/$key is still English: ${untranslated.first().trim()}"
                }
                if (untranslated.isEmpty() && key in cognates) {
                    problems += "$locale/$key is translated now; drop it from SAME_IN_LANGUAGE"
                }
                if (key in SAME_IN_EVERY_LANGUAGE && entry.values != english.values) {
                    problems += "$locale/$key is a name and must stay as in values/, or leave SAME_IN_EVERY_LANGUAGE"
                }
            }
            (cognates - bundles.getValue(locale).entries.keys).forEach {
                problems += "SAME_IN_LANGUAGE lists $locale/$it, which does not exist"
            }
        }
        assertNoProblems(problems, "Strings left in English")
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
        assertTrue(
            bundles.getValue(SOURCE).files.size > 1,
            "values/ holds only ${bundles.getValue(SOURCE).files.keys}; the side files went missing or are not parsed",
        )
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

    /** One values folder: every .xml file in it, by file name, and all their entries merged. */
    private class Bundle(val files: Map<String, Map<String, Entry>>) {
        val entries: Map<String, Entry> = files.values.fold(emptyMap()) { all, file -> all + file }
    }

    private companion object {
        const val SOURCE = "values"

        val LOCALES = listOf(
            "values-ru", "values-ro", "values-es", "values-pt-rBR", "values-pt", "values-de", "values-fr",
            "values-it", "values-zh-rCN", "values-zh", "values-ja", "values-ko", "values-tr", "values-uk",
            "values-pl", "values-id", "values-in", "values-ar", "values-hi", "values-he", "values-iw",
        )

        val PLACEHOLDER = Regex("%(\\d+)\\$")

        /** Names: never translated, so identical to English in every locale (and checked to be). */
        val SAME_IN_EVERY_LANGUAGE = setOf(
            "player_mode_smart", "settings_color_smart", "settings_license_runtime", "output_bluetooth",
        )

        /**
         * Strings a language really writes the same as English, checked by a person: shared words
         * (German "Album", French "Playlists") and the units a language keeps in Latin letters
         * ("kbps", "MB", "min"). A unit is listed only for the locales that keep it.
         */
        val SAME_IN_LANGUAGE: Map<String, Set<String>> = run {
            val kbpsAndMegabytes = setOf("duplicates_kbps", "unit_megabytes")
            val latinTimeUnits = setOf(
                "settings_crossfade_value", "stats_hours_short", "stats_minutes_short", "stats_under_minute",
            )
            val indonesian = kbpsAndMegabytes + setOf(
                "action_edit_album", "action_edit_short", "count_albums", "count_files", "details_file",
                "details_format", "equalizer_preset_treble", "info_album", "info_genre",
                "intelligence_section_status",
            )
            mapOf(
                "values-de" to setOf(
                    "action_pause", "details_format", "equalizer_preset_bass", "info_album", "info_cover",
                    "info_genre", "intelligence_engine", "intelligence_section_status", "settings_equalizer",
                    "settings_section_navigation", "settings_version", "tab_genres", "tab_playlists",
                    "unit_megabytes", "settings_crossfade_value",
                ),
                "values-es" to kbpsAndMegabytes + latinTimeUnits + setOf("equalizer_preset_vocal", "stats_days_short"),
                "values-fr" to latinTimeUnits + setOf(
                    "action_pause", "count_albums", "details_format", "info_album", "info_genre",
                    "settings_pages", "settings_section_navigation", "settings_version",
                    "sleep_timer_minutes", "stats_streak_longest", "tab_albums", "tab_genres", "tab_playlists",
                ),
                "values-he" to kbpsAndMegabytes,
                "values-iw" to kbpsAndMegabytes,
                "values-hi" to kbpsAndMegabytes,
                "values-id" to indonesian,
                "values-in" to indonesian,
                "values-it" to kbpsAndMegabytes + latinTimeUnits + setOf(
                    "count_albums", "count_files", "details_file", "info_album", "stats_streak_longest",
                ),
                "values-ja" to kbpsAndMegabytes,
                "values-ko" to kbpsAndMegabytes,
                "values-pl" to setOf(
                    "count_albums", "details_format", "info_album", "unit_megabytes", "settings_crossfade_value",
                    "stats_minutes_short", "stats_under_minute",
                ),
                "values-pt-rBR" to kbpsAndMegabytes + latinTimeUnits + setOf(
                    "equalizer_preset_vocal", "intelligence_section_status", "tab_playlists", "stats_days_short",
                ),
                "values-pt" to kbpsAndMegabytes + latinTimeUnits + setOf(
                    "equalizer_preset_vocal", "intelligence_section_status", "tab_playlists", "stats_days_short",
                ),
                "values-ro" to kbpsAndMegabytes + latinTimeUnits + setOf(
                    "count_albums", "details_format", "equalizer_preset_electronic", "info_album", "info_artist",
                    "sleep_timer_minutes", "stats_streak_longest",
                ),
                "values-tr" to setOf("unit_megabytes"),
                "values-zh-rCN" to kbpsAndMegabytes,
                "values-zh" to kbpsAndMegabytes,
            )
        }

        /** Anything left once placeholders and \uXXXX escapes are gone that is a letter. */
        fun String.hasWords(): Boolean =
            replace(PLACEHOLDER_TOKEN, "").replace(UNICODE_ESCAPE, "").any { it.isLetter() }

        val PLACEHOLDER_TOKEN = Regex("%\\d+\\$[a-z]")
        val UNICODE_ESCAPE = Regex("\\\\u[0-9a-fA-F]{4}")

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

        /**
         * Android res folders, by path from the repository root. Listed rather than discovered, so
         * a moved folder fails loudly instead of quietly dropping out of the check.
         */
        val androidResourceRoots: Map<String, File> by lazy {
            // resourcesDir is <repository>/composeApp/src/commonMain/composeResources.
            val repository = resourcesDir.resolve("../../../..").normalize()
            listOf("composeApp/src/androidMain/res", "core/playback/src/androidMain/res")
                .associateWith { File(repository, it) }
        }

        /** Folders Android resolves only under the legacy language code, and that code's folder. */
        val LEGACY_CODES = mapOf("values-id" to "values-in", "values-he" to "values-iw")

        fun xmlFiles(bundle: String): List<File> = xmlFiles(File(resourcesDir, bundle))

        fun xmlFiles(folder: File): List<File> =
            folder.listFiles { f: File -> f.isFile && f.name.endsWith(".xml") }
                .orEmpty()
                .sortedBy { it.name }

        /** The `<resources>` element onward, so a differing header comment is not a difference. */
        fun body(file: File): String {
            val text = file.readText()
            val start = text.indexOf("<resources>")
            assertTrue(start >= 0, "$file has no <resources> element")
            return text.substring(start)
        }

        /**
         * [copy] holds exactly the files of [original], each with the same body. The files need
         * not be byte-identical — the copy's strings.xml carries its own header comment.
         */
        fun assertFaithfulDuplicate(original: String, copy: String) {
            val originals = xmlFiles(original).map { it.name }
            assertEquals(originals, xmlFiles(copy).map { it.name }, "$copy and $original hold different files")
            originals.forEach { name ->
                assertEquals(
                    body(File(resourcesDir, "$original/$name")),
                    body(File(resourcesDir, "$copy/$name")),
                    "$copy/$name drifted from $original/$name",
                )
            }
        }

        /** [assertFaithfulDuplicate] for two folders under any [root], reported as problems. */
        fun duplicateProblems(root: File, original: String, copy: String): List<String> {
            val originals = xmlFiles(File(root, original)).map { it.name }
            if (originals != xmlFiles(File(root, copy)).map { it.name }) {
                return listOf("$copy and $original hold different files")
            }
            return originals
                .filter { body(File(root, "$original/$it")) != body(File(root, "$copy/$it")) }
                .map { "$copy/$it drifted from $original/$it" }
        }

        fun parse(bundle: String): Bundle {
            val files = xmlFiles(bundle)
            assertTrue(files.any { it.name == "strings.xml" }, "Missing $bundle/strings.xml")
            return Bundle(files.associate { it.name to parseFile(it) })
        }

        fun parseFile(source: File): Map<String, Entry> {
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
            return entries
        }

        fun org.w3c.dom.NodeList.elements(): List<Element> =
            (0 until length).map { item(it) as Element }

        fun assertNoProblems(problems: List<String>, headline: String) {
            if (problems.isEmpty()) return
            fail("$headline — ${problems.size} problem(s):\n" + problems.joinToString("\n") { "  - $it" })
        }
    }
}
