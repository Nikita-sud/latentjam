/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

internal class NativeIdentifiersTest {

    @Test
    fun `a comma in a backticked test name is refused on its line`() {
        val source = kt(
            """
            package p

            internal class QueueTest {
                @Test
                fun `plays the next track, then stops`() {}
            }
            """,
        )

        assertEquals(listOf(RefusedName(5, "plays the next track, then stops", ",")), refusedNames(source))
    }

    @Test
    fun `each refused character is named once in the order it first appears`() {
        assertEquals(
            listOf(RefusedName(1, "why? (and when, and how)", "?(,)")),
            refusedNames("fun `why? (and when, and how)`() {}"),
        )
    }

    @Test
    fun `every kind of declaration is checked`() {
        val source = kt(
            """
            class `a,b`<`c,d`>(val `e,f`: Int) {
                enum class Kind { `g,h` }
                fun take(`i,j`: Int) { val `k,l` = 1 }
            }
            typealias `m,n` = Int
            """,
        )

        assertEquals(listOf("a,b", "c,d", "e,f", "g,h", "i,j", "k,l", "m,n"), refusedNames(source).map { it.name })
    }

    @Test
    fun `names Kotlin Native accepts pass`() {
        val source = kt(
            """
            fun `a track queued by hand is the listener's pick`() {}
            fun `skips → next — even at 100 percent`() {}
            fun `says "hi" and leaves`() {}
            val `in` = 1
            """,
        )

        assertEquals(emptyList(), refusedNames(source))
    }

    @Test
    fun `backticks in comments are prose`() {
        val source = kt(
            """
            // `a, b` in a line comment
            /** KDoc names `c.d()` and `e, f`. */
            /* outer /* nested `g, h` */ still the outer comment `i, j`
               across lines `k, l` */
            fun `m, n`() {}
            """,
        )

        assertEquals(listOf(RefusedName(5, "m, n", ",")), refusedNames(source))
    }

    @Test
    fun `backticks in strings and char literals are text`() {
        val source = kt(
            """
            val a = "quoted `a, b` and an escaped \" `c, d`"
            val b = '`'; fun `e, f`() {}
            val c = '"'; fun `g, h`() {}
            val d = '\''; fun `i, j`() {}
            """,
        )

        assertEquals(
            listOf(RefusedName(2, "e, f", ","), RefusedName(3, "g, h", ","), RefusedName(4, "i, j", ",")),
            refusedNames(source),
        )
    }

    @Test
    fun `comment markers inside strings open no comment`() {
        val source = kt(
            """
            val site = "https://example.org"; fun `a, b`() {}
            val glob = "src/*"
            fun `c, d`() {}
            """,
        )

        assertEquals(listOf(RefusedName(1, "a, b", ","), RefusedName(3, "c, d", ",")), refusedNames(source))
    }

    @Test
    fun `raw strings keep backslashes and end on their last quote`() {
        val source = kt(
            $$"""
            val stamp = Regex('''^\[(\d{1,3})\]$''')
            val quoted = '''"it holds `a, b`
            and ends on a quote"'''
            fun `c, d`() {}
            """,
        )

        assertEquals(listOf(RefusedName(4, "c, d", ",")), refusedNames(source))
    }

    @Test
    fun `the code of a string template is still code`() {
        val source = kt(
            $$"""
            val a = "${ `a, b`() }"
            val b = "$`c, d`"
            val c = "${ items.map { it } } then `e, f` as text"
            val d = "${ "a nested `g, h` string" + `i, j` }"
            val e = '''
                ${ `k, l` } then `m, n` as text
            '''
            """,
        )

        assertEquals(listOf("a, b", "c, d", "i, j", "k, l"), refusedNames(source).map { it.name })
        assertEquals(listOf(1, 2, 4, 6), refusedNames(source).map { it.line })
    }

    @Test
    fun `a multi-dollar string needs as many dollars to open a template`() {
        val source = kt(
            $$$"""
            val json = $$'''{ "a": ${ b'''
            val template = $$'''$${ `c, d` }'''
            fun `e, f`() {}
            """,
        )

        assertEquals(listOf(RefusedName(2, "c, d", ","), RefusedName(3, "e, f", ",")), refusedNames(source))
    }

    @Test
    fun `windows line endings count lines the same`() {
        assertEquals(
            listOf(RefusedName(2, "c, d", ",")),
            refusedNames("fun `ab`() {}\r\nfun `c, d`() {}\r\n"),
        )
    }

    /** Kotlin source written in a raw string, where `'''` stands for the triple quote it cannot hold. */
    private fun kt(source: String): String = source.trimIndent().replace("'''", "\"\"\"")
}
