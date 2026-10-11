/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

/**
 * Compares names the way a file explorer lists them: runs of ASCII digits by their value, so
 * "2 Intro" comes before "10 Outro", everything else letter by letter ignoring case. Leading zeros
 * do not count ("02" equals "2"); when two names are otherwise equal the plain comparison decides,
 * so the order is total.
 */
internal fun compareNatural(left: String, right: String): Int {
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val a = left[i]
        val b = right[j]
        if (a in '0'..'9' && b in '0'..'9') {
            var aEnd = i
            while (aEnd < left.length && left[aEnd] in '0'..'9') aEnd++
            var bEnd = j
            while (bEnd < right.length && right[bEnd] in '0'..'9') bEnd++
            var aStart = i
            while (aStart < aEnd - 1 && left[aStart] == '0') aStart++
            var bStart = j
            while (bStart < bEnd - 1 && right[bStart] == '0') bStart++
            val aLength = aEnd - aStart
            val bLength = bEnd - bStart
            if (aLength != bLength) return aLength.compareTo(bLength)
            for (k in 0 until aLength) {
                val digit = left[aStart + k].compareTo(right[bStart + k])
                if (digit != 0) return digit
            }
            i = aEnd
            j = bEnd
        } else {
            val letter = a.lowercaseChar().compareTo(b.lowercaseChar())
            if (letter != 0) return letter
            i++
            j++
        }
    }
    val rest = (left.length - i).compareTo(right.length - j)
    return if (rest != 0) rest else left.compareTo(right)
}
