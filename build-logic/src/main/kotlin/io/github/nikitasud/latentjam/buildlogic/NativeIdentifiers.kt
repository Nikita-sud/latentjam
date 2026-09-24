/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.buildlogic

/**
 * The characters Kotlin/Native refuses in a declaration name ("Name contains illegal characters").
 *
 * This is `FirNativeIdentifierChecker.invalidChars` of Kotlin 2.3.10, read from the checker's static
 * initializer in `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.10/konan/lib/kotlin-native-compiler-embeddable.jar`.
 * After a Kotlin bump, compare it with the new compiler's: in the output of
 *
 *     javap -c -classpath <that jar> org.jetbrains.kotlin.fir.analysis.native.checkers.FirNativeIdentifierChecker
 *
 * each `Character.valueOf` in `static {}` follows the push of one character code.
 *
 * The JVM refuses only `. ; [ ] / < > : \`, which is how the Android host tests compile names the
 * iOS compile cannot.
 */
internal const val NATIVE_REFUSED_CHARACTERS = """.;,()[]{}/<>:\$&~*?#|§%@"""

/** A backticked [name] on [line] holding the [refused] characters, each once, in order of appearance. */
internal data class RefusedName(val line: Int, val name: String, val refused: String)

/**
 * Every backticked name in the code of [source] that holds a character of [NATIVE_REFUSED_CHARACTERS].
 *
 * Comments (nested ones too), string literals and char literals are skipped, since backticks there are
 * prose; the code inside a string template is still code. References are checked along with
 * declarations: a reference can only name a declaration that the rule already refuses, and telling the
 * two apart would take a parser.
 */
internal fun refusedNames(source: String): List<RefusedName> = Scanner(source).scan()

/** What encloses the scan position: code, or a string opened by [Str.dollars] dollar signs. */
private sealed interface Mode

/** Top-level code, or the code of a `${…}` [template] that the `}` at [braces] zero closes. */
private class Code(val template: Boolean) : Mode {
    var braces = 0
}

private class Str(val raw: Boolean, val dollars: Int) : Mode

private class Scanner(private val text: String) {
    private var at = 0
    private var line = 1
    private val modes = ArrayDeque<Mode>(listOf(Code(template = false)))
    private val found = mutableListOf<RefusedName>()

    fun scan(): List<RefusedName> {
        while (at < text.length) {
            when (val mode = modes.last()) {
                is Code -> code(mode)
                is Str -> string(mode)
            }
        }
        return found
    }

    private fun code(mode: Code) {
        val c = text[at]
        when {
            text.startsWith("//", at) -> while (at < text.length && text[at] != '\n') step()
            text.startsWith("/*", at) -> blockComment()
            c == '"' -> openString(prefix = 0)
            c == '$' && text.getOrNull(at + dollarRun()) == '"' -> openString(prefix = dollarRun())
            c == '\'' -> charLiteral()
            c == '`' -> name()
            c == '{' -> {
                mode.braces++
                step()
            }
            c == '}' && mode.template && mode.braces == 0 -> {
                modes.removeLast()
                step()
            }
            c == '}' -> {
                mode.braces--
                step()
            }
            else -> step()
        }
    }

    private fun string(mode: Str) {
        val c = text[at]
        when {
            mode.raw && text.startsWith("\"\"\"", at) -> {
                // A raw string ends on the last three quotes of a run: """a"""" holds a" .
                while (at < text.length && text[at] == '"') step()
                modes.removeLast()
            }
            !mode.raw && c == '"' -> {
                step()
                modes.removeLast()
            }
            !mode.raw && c == '\\' -> step(2)
            // A plain string cannot cross a line; the compiler reports that, the scan carries on as code.
            !mode.raw && c == '\n' -> modes.removeLast()
            c == '$' -> template(mode)
            else -> step()
        }
    }

    /** `$$"…"` needs two dollar signs to open a template, `$"…"` one; fewer are text. */
    private fun template(mode: Str) {
        val run = dollarRun()
        val next = text.getOrNull(at + run)
        step(run)
        if (run < mode.dollars) return
        when (next) {
            '{' -> {
                step()
                modes.addLast(Code(template = true))
            }
            '`' -> name()
            // `$name` cannot hold a refused character; anything else was a literal dollar sign.
        }
    }

    /** Opens the string whose quote follows [prefix] dollar signs (`$$"…"`); without any, `$` is enough. */
    private fun openString(prefix: Int) {
        step(prefix)
        val raw = text.startsWith("\"\"\"", at)
        step(if (raw) 3 else 1)
        modes.addLast(Str(raw, dollars = maxOf(prefix, 1)))
    }

    private fun blockComment() {
        var depth = 0
        while (at < text.length) {
            when {
                text.startsWith("/*", at) -> {
                    depth++
                    step(2)
                }
                text.startsWith("*/", at) -> {
                    depth--
                    step(2)
                    if (depth == 0) return
                }
                else -> step()
            }
        }
    }

    private fun charLiteral() {
        step()
        while (at < text.length) {
            when (text[at]) {
                '\\' -> step(2)
                '\'' -> return step()
                '\n' -> return
                else -> step()
            }
        }
    }

    /** A backticked name ends on the next backtick; one that reaches the line's end is not a name. */
    private fun name() {
        val end = text.indexOfAny(charArrayOf('`', '\n'), startIndex = at + 1).takeIf { it >= 0 } ?: text.length
        if (end < text.length && text[end] == '`') {
            val name = text.substring(at + 1, end)
            val refused = name.filter { it in NATIVE_REFUSED_CHARACTERS }.toSet().joinToString("")
            if (refused.isNotEmpty()) found += RefusedName(line, name, refused)
            at = end + 1
        } else {
            at = end
        }
    }

    private fun dollarRun(): Int {
        var run = 0
        while (text.getOrNull(at + run) == '$') run++
        return run
    }

    private fun step(count: Int = 1) {
        repeat(count) {
            if (at < text.length) {
                if (text[at] == '\n') line++
                at++
            }
        }
    }
}
