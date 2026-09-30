/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [TextRepair] to the rule it was measured as: every pair is an input and the output
 * of the Python model the rule was designed and measured on (audit 2026-09-29,
 * `cp1250-sim/sim2.py`, `final_repair` with `lone=True`). The groups are the genuine all-caps
 * and mixed-case Central-European and Turkish names a review found garbled; the nine MusicBrainz names that
 * are themselves mojibake (true repairs); each genuine name mangled once through cp1252,
 * Windows-1250 and Windows-1251; and names from the measured recall loss, mangled through the
 * table they were lost in, which the rule deliberately leaves as read; and the standalone
 * short words the fallback tables must not touch ("Ні 2"), next to what they still repair.
 */
internal class TextRepairVectorsTest {

    @Test
    fun repairMatchesTheMeasuredRuleOnEveryVector() {
        val mismatches = VECTORS.filter { (input, expected) -> TextRepair.repair(input) != expected }
            .map { (input, expected) -> "$input -> ${TextRepair.repair(input)} (expected $expected)" }
        assertEquals(emptyList(), mismatches)
    }

    private companion object {
        val VECTORS: List<Pair<String, String>> = listOf(
            "P\u00D3\u0141NOC" to "P\u00D3\u0141NOC", // genuine
            "P\u00F3\u0142noc" to "P\u00F3\u0142noc", // genuine
            "R\u00D3\u017BNI WYKONAWCY" to "R\u00D3\u017BNI WYKONAWCY", // genuine
            "R\u00F3\u017Cni wykonawcy" to "R\u00F3\u017Cni wykonawcy", // genuine
            "KSI\u0118\u017BYC" to "KSI\u0118\u017BYC", // genuine
            "Ksi\u0119\u017Cyc" to "Ksi\u0119\u017Cyc", // genuine
            "M\u016E\u017DE" to "M\u016E\u017DE", // genuine
            "M\u016F\u017Ee" to "M\u016F\u017Ee", // genuine
            "T\u011A\u017DKEJ POKONDR" to "T\u011A\u017DKEJ POKONDR", // genuine
            "T\u011B\u017Ekej Pokondr" to "T\u011B\u017Ekej Pokondr", // genuine
            "K\u00D6\u015EE" to "K\u00D6\u015EE", // genuine
            "K\u00F6\u015Fe" to "K\u00F6\u015Fe", // genuine
            "M\u00DC\u015EFIK KENTER" to "M\u00DC\u015EFIK KENTER", // genuine
            "M\u00FC\u015Ffik Kenter" to "M\u00FC\u015Ffik Kenter", // genuine
            "\u00CE\u015Fi face loc" to "\u00CE\u015Fi face loc", // genuine
            "\u00CE\u015EI FACE LOC" to "\u00CE\u015EI FACE LOC", // genuine
            "RADEK POSP\u00CD\u0160IL" to "RADEK POSP\u00CD\u0160IL", // genuine
            "Radek Posp\u00ED\u0161il" to "Radek Posp\u00ED\u0161il", // genuine
            "MAT\u00DA\u0160" to "MAT\u00DA\u0160", // genuine
            "Mat\u00FA\u0161" to "Mat\u00FA\u0161", // genuine
            "\u00DA\u017Eas" to "\u00DA\u017Eas", // genuine
            "\u00DA\u017DAS" to "\u00DA\u017DAS", // genuine
            "PETR SEP\u00C9\u0160I" to "PETR SEP\u00C9\u0160I", // genuine
            "Petr Sep\u00E9\u0161i" to "Petr Sep\u00E9\u0161i", // genuine
            "D\u00E9\u0161\u0165" to "D\u00E9\u0161\u0165", // genuine
            "d\u00E9\u0161\u0165" to "d\u00E9\u0161\u0165", // genuine
            "Bj\u00C3\u00B6rk" to "Bj\u00F6rk", // repair (repaired)
            "Coralie Cl\u00C3\u00A9ment" to "Coralie Cl\u00E9ment", // repair (repaired)
            "Ir\u00C3\u0083\u00C2\u00A1n Castillo" to "Ir\u00E1n Castillo", // repair (repaired)
            "Jos\u00C3\u00A9 Gonz\u00C3\u00A1lez" to "Jos\u00E9 Gonz\u00E1lez", // repair (repaired)
            "M\u00C3\u00BAm" to "M\u00FAm", // repair (repaired)
            "R\u00C3\u00BCdiger Hoffmann" to "R\u00FCdiger Hoffmann", // repair (repaired)
            "Slagsm\u00C3\u00A5lsklubben" to "Slagsm\u00E5lsklubben", // repair (repaired)
            "\u00C2\u00B5-Ziq" to "\u00B5-Ziq", // repair (repaired)
            "\u00C7\u2020" to "\u01C6", // repair (repaired)
            "P\u00C3\u201C\u00C5\u0081NOC" to "P\u00D3\u0141NOC", // moj-cp1252 (repaired)
            "P\u0102\u201C\u0139\u0081NOC" to "P\u00D3\u0141NOC", // moj-cp1250 (repaired)
            "P\u0413\u201C\u0415\u0403NOC" to "P\u0413\u201C\u0415\u0403NOC", // moj-cp1251
            "P\u00C3\u00B3\u00C5\u201Anoc" to "P\u00F3\u0142noc", // moj-cp1252 (repaired)
            "P\u0102\u0142\u0139\u201Anoc" to "P\u00F3\u0142noc", // moj-cp1250 (repaired)
            "P\u0413\u0456\u0415\u201Anoc" to "P\u00F3\u0142noc", // moj-cp1251 (repaired)
            "R\u00C3\u201C\u00C5\u00BBNI WYKONAWCY" to "R\u00D3\u017BNI WYKONAWCY", // moj-cp1252 (repaired)
            "R\u0102\u201C\u0139\u00BBNI WYKONAWCY" to "R\u00D3\u017BNI WYKONAWCY", // moj-cp1250 (repaired)
            "R\u0413\u201C\u0415\u00BBNI WYKONAWCY" to "R\u00D3\u017BNI WYKONAWCY", // moj-cp1251 (repaired)
            "R\u00C3\u00B3\u00C5\u00BCni wykonawcy" to "R\u00F3\u017Cni wykonawcy", // moj-cp1252 (repaired)
            "R\u0102\u0142\u0139\u013Dni wykonawcy" to "R\u00F3\u017Cni wykonawcy", // moj-cp1250 (repaired)
            "R\u0413\u0456\u0415\u0458ni wykonawcy" to "R\u00F3\u017Cni wykonawcy", // moj-cp1251 (repaired)
            "KSI\u00C4\u02DC\u00C5\u00BBYC" to "KSI\u0118\u017BYC", // moj-cp1252 (repaired)
            "KSI\u00C4\u0098\u0139\u00BBYC" to "KSI\u0118\u017BYC", // moj-cp1250 (repaired)
            "KSI\u0414\u0098\u0415\u00BBYC" to "KSI\u0118\u017BYC", // moj-cp1251 (repaired)
            "Ksi\u00C4\u2122\u00C5\u00BCyc" to "Ksi\u0119\u017Cyc", // moj-cp1252 (repaired)
            "Ksi\u00C4\u2122\u0139\u013Dyc" to "Ksi\u0119\u017Cyc", // moj-cp1250 (repaired)
            "Ksi\u0414\u2122\u0415\u0458yc" to "Ksi\u0119\u017Cyc", // moj-cp1251 (repaired)
            "M\u00C5\u00AE\u00C5\u00BDE" to "M\u016E\u017DE", // moj-cp1252 (repaired)
            "M\u0139\u00AE\u0139\u02DDE" to "M\u016E\u017DE", // moj-cp1250 (repaired)
            "M\u0415\u00AE\u0415\u0405E" to "M\u0415\u00AE\u0415\u0405E", // moj-cp1251
            "M\u00C5\u00AF\u00C5\u00BEe" to "M\u016F\u017Ee", // moj-cp1252 (repaired)
            "M\u0139\u017B\u0139\u013Ee" to "M\u016F\u017Ee", // moj-cp1250 (repaired)
            "M\u0415\u0407\u0415\u0455e" to "M\u016F\u017Ee", // moj-cp1251 (repaired)
            "T\u00C4\u0161\u00C5\u00BDKEJ POKONDR" to "T\u011A\u017DKEJ POKONDR", // moj-cp1252 (repaired)
            "T\u00C4\u0161\u0139\u02DDKEJ POKONDR" to "T\u011A\u017DKEJ POKONDR", // moj-cp1250 (repaired)
            "T\u0414\u0459\u0415\u0405KEJ POKONDR" to "T\u011A\u017DKEJ POKONDR", // moj-cp1251 (repaired)
            "T\u00C4\u203A\u00C5\u00BEkej Pokondr" to "T\u011B\u017Ekej Pokondr", // moj-cp1252 (repaired)
            "T\u00C4\u203A\u0139\u013Ekej Pokondr" to "T\u011B\u017Ekej Pokondr", // moj-cp1250 (repaired)
            "T\u0414\u203A\u0415\u0455kej Pokondr" to "T\u011B\u017Ekej Pokondr", // moj-cp1251 (repaired)
            "K\u00C3\u2013\u00C5\u017EE" to "K\u00D6\u015EE", // moj-cp1252 (repaired)
            "K\u0102\u2013\u0139\u017EE" to "K\u00D6\u015EE", // moj-cp1250 (repaired)
            "K\u0413\u2013\u0415\u045BE" to "K\u00D6\u015EE", // moj-cp1251 (repaired)
            "K\u00C3\u00B6\u00C5\u0178e" to "K\u00F6\u015Fe", // moj-cp1252 (repaired)
            "K\u0102\u00B6\u0139\u017Ae" to "K\u00F6\u015Fe", // moj-cp1250 (repaired)
            "K\u0413\u00B6\u0415\u045Fe" to "K\u00F6\u015Fe", // moj-cp1251 (repaired)
            "M\u00C3\u0153\u00C5\u017EFIK KENTER" to "M\u00DC\u015EFIK KENTER", // moj-cp1252 (repaired)
            "M\u0102\u015B\u0139\u017EFIK KENTER" to "M\u00DC\u015EFIK KENTER", // moj-cp1250 (repaired)
            "M\u0413\u045A\u0415\u045BFIK KENTER" to "M\u00DC\u015EFIK KENTER", // moj-cp1251 (repaired)
            "M\u00C3\u00BC\u00C5\u0178fik Kenter" to "M\u00FC\u015Ffik Kenter", // moj-cp1252 (repaired)
            "M\u0102\u013D\u0139\u017Afik Kenter" to "M\u00FC\u015Ffik Kenter", // moj-cp1250 (repaired)
            "M\u0413\u0458\u0415\u045Ffik Kenter" to "M\u00FC\u015Ffik Kenter", // moj-cp1251 (repaired)
            "\u00C3\u017D\u00C5\u0178i face loc" to "\u00CE\u015Fi face loc", // moj-cp1252 (repaired)
            "\u0102\u017D\u0139\u017Ai face loc" to "\u00CE\u015Fi face loc", // moj-cp1250 (repaired)
            "\u0413\u040B\u0415\u045Fi face loc" to "\u00CE\u015Fi face loc", // moj-cp1251 (repaired)
            "\u00C3\u017D\u00C5\u017EI FACE LOC" to "\u00CE\u015EI FACE LOC", // moj-cp1252 (repaired)
            "\u0102\u017D\u0139\u017EI FACE LOC" to "\u00CE\u015EI FACE LOC", // moj-cp1250 (repaired)
            "\u0413\u040B\u0415\u045BI FACE LOC" to "\u00CE\u015EI FACE LOC", // moj-cp1251 (repaired)
            "RADEK POSP\u00C3\u008D\u00C5\u00A0IL" to "RADEK POSP\u00CD\u0160IL", // moj-cp1252 (repaired)
            "RADEK POSP\u0102\u0164\u0139\u00A0IL" to "RADEK POSP\u0102\u0164\u0139\u00A0IL", // moj-cp1250
            "RADEK POSP\u0413\u040C\u0415\u00A0IL" to "RADEK POSP\u0413\u040C\u0415\u00A0IL", // moj-cp1251
            "Radek Posp\u00C3\u00AD\u00C5\u00A1il" to "Radek Posp\u00ED\u0161il", // moj-cp1252 (repaired)
            "Radek Posp\u0102\u00AD\u0139\u02C7il" to "Radek Posp\u00ED\u0161il", // moj-cp1250 (repaired)
            "Radek Posp\u0413\u00AD\u0415\u040Eil" to "Radek Posp\u00ED\u0161il", // moj-cp1251 (repaired)
            "MAT\u00C3\u0161\u00C5\u00A0" to "MAT\u00DA\u0160", // moj-cp1252 (repaired)
            "MAT\u0102\u0161\u0139\u00A0" to "MAT\u00DA\u0160", // moj-cp1250 (repaired)
            "MAT\u0413\u0459\u0415\u00A0" to "MAT\u00DA\u0160", // moj-cp1251 (repaired)
            "Mat\u00C3\u00BA\u00C5\u00A1" to "Mat\u00FA\u0161", // moj-cp1252 (repaired)
            "Mat\u0102\u015F\u0139\u02C7" to "Mat\u00FA\u0161", // moj-cp1250 (repaired)
            "Mat\u0413\u0454\u0415\u040E" to "Mat\u00FA\u0161", // moj-cp1251 (repaired)
            "\u00C3\u0161\u00C5\u00BEas" to "\u00DA\u017Eas", // moj-cp1252 (repaired)
            "\u0102\u0161\u0139\u013Eas" to "\u00DA\u017Eas", // moj-cp1250 (repaired)
            "\u0413\u0459\u0415\u0455as" to "\u00DA\u017Eas", // moj-cp1251 (repaired)
            "\u00C3\u0161\u00C5\u00BDAS" to "\u00DA\u017DAS", // moj-cp1252 (repaired)
            "\u0102\u0161\u0139\u02DDAS" to "\u00DA\u017DAS", // moj-cp1250 (repaired)
            "\u0413\u0459\u0415\u0405AS" to "\u00DA\u017DAS", // moj-cp1251 (repaired)
            "PETR SEP\u00C3\u2030\u00C5\u00A0I" to "PETR SEP\u00C9\u0160I", // moj-cp1252 (repaired)
            "PETR SEP\u0102\u2030\u0139\u00A0I" to "PETR SEP\u00C9\u0160I", // moj-cp1250 (repaired)
            "PETR SEP\u0413\u2030\u0415\u00A0I" to "PETR SEP\u00C9\u0160I", // moj-cp1251 (repaired)
            "Petr Sep\u00C3\u00A9\u00C5\u00A1i" to "Petr Sep\u00E9\u0161i", // moj-cp1252 (repaired)
            "Petr Sep\u0102\u00A9\u0139\u02C7i" to "Petr Sep\u00E9\u0161i", // moj-cp1250 (repaired)
            "Petr Sep\u0413\u00A9\u0415\u040Ei" to "Petr Sep\u00E9\u0161i", // moj-cp1251 (repaired)
            "D\u00C3\u00A9\u00C5\u00A1\u00C5\u00A5" to "D\u00E9\u0161\u0165", // moj-cp1252 (repaired)
            "D\u0102\u00A9\u0139\u02C7\u0139\u0104" to "D\u00E9\u0161\u0165", // moj-cp1250 (repaired)
            "D\u0413\u00A9\u0415\u040E\u0415\u0490" to "D\u00E9\u0161\u0165", // moj-cp1251 (repaired)
            "d\u00C3\u00A9\u00C5\u00A1\u00C5\u00A5" to "d\u00E9\u0161\u0165", // moj-cp1252 (repaired)
            "d\u0102\u00A9\u0139\u02C7\u0139\u0104" to "d\u00E9\u0161\u0165", // moj-cp1250 (repaired)
            "d\u0413\u00A9\u0415\u040E\u0415\u0490" to "d\u00E9\u0161\u0165", // moj-cp1251 (repaired)
            "\u00C9\u0090\u00C9\u0105\u0118\u2021s\u00C7\u0165\u00C9\u0104\u00C9\u201D\u0118\u015Ao\u00C9\u0105d\u00C9\u017BI \u0118\u017Duxn\u00C9\u0090\u00C9\u017A\u00C9\u017B\u0118\u017DS s,\u00C9\u017A\u00C7\u0165\u00C4\u00B1\u00C9\u0104\u0118\u2021\u00C9\u017B\u00C9\u0090\u00C7\u0165\u00C9\u0105\u00E1\u2014\u02C7 \u00C7\u0165\u00C9\u0104\u00E2\u0160\u0104 \u00C7\u0165uoO" to "\u00C9\u0090\u00C9\u0105\u0118\u2021s\u00C7\u0165\u00C9\u0104\u00C9\u201D\u0118\u015Ao\u00C9\u0105d\u00C9\u017BI \u0118\u017Duxn\u00C9\u0090\u00C9\u017A\u00C9\u017B\u0118\u017DS s,\u00C9\u017A\u00C7\u0165\u00C4\u00B1\u00C9\u0104\u0118\u2021\u00C9\u017B\u00C9\u0090\u00C7\u0165\u00C9\u0105\u00E1\u2014\u02C7 \u00C7\u0165\u00C9\u0104\u00E2\u0160\u0104 \u00C7\u0165uoO", // lost-cp1250
            "Ya\u011A\u00A7nomam\u0102\u00B6" to "Ya\u011A\u00A7nomam\u0102\u00B6", // lost-cp1250
            "\u00E9\u0160\u20AC\u0107\u0165\u0179BOYZ" to "\u00E9\u0160\u20AC\u0107\u0165\u0179BOYZ", // lost-cp1250
            "L\u00CE\u00A9ST" to "L\u00CE\u00A9ST", // lost-cp1250
            "R\u00CE\u0104MD\u00CE\u0161R\u00CE\u203AFT" to "R\u00CE\u0104MD\u00CE\u0161R\u00CE\u203AFT", // lost-cp1250
            "\u0111\u0165\u201D\u2021\u0111\u0165\u201D\u0164 6YR\u0155\u00B0\u0104\u0155\u00B1\u0164\u00E2\u20AC\u015A\u016E\u0141\u016E\u0141A \u0110\u2014\u0110\u0090\u0110\u017E" to "\u0111\u0165\u201D\u2021\u0111\u0165\u201D\u0164 6YR\u0155\u00B0\u0104\u0155\u00B1\u0164\u00E2\u20AC\u015A\u016E\u0141\u016E\u0141A \u0110\u2014\u0110\u0090\u0110\u017E", // lost-cp1250
            "\u010E\u0088 Boonagh \u010E\u20ACsonne\u00E2\u20AC\u2122s \u010E\u0083phonia \u010E\u2030estra" to "\u010E\u0088 Boonagh \u010E\u20ACsonne\u00E2\u20AC\u2122s \u010E\u0083phonia \u010E\u2030estra", // lost-cp1250
            "blush\u0118\u2022 \u0119\u0088\u0164\u00E1\u00B4\u0104\u0119\u0088\u0164\u0118\u201D" to "blush\u0118\u2022 \u0119\u0088\u0164\u00E1\u00B4\u0104\u0119\u0088\u0164\u0118\u201D", // lost-cp1250
            "Ariel Ram\u00CE\u017Brez" to "Ariel Ram\u00CE\u017Brez", // lost-cp1250
            "B\u00CE\u0141retta Crossrain" to "B\u00CE\u0141retta Crossrain", // lost-cp1250
            "age\u041A\u045Aa" to "age\u041A\u045Aa", // lost-cp1251
            "\u0420\u045AEIKLAV" to "\u0420\u045AEIKLAV", // lost-cp1251
            "\u0422\u045A\u0420\u0098\u0420\u2013\u0420\u00A9L\u041E\u045BDG\u041E\u045B" to "\u0422\u045A\u0420\u0098\u0420\u2013\u0420\u00A9L\u041E\u045BDG\u041E\u045B", // lost-cp1251
            "Luiza S\u0413\u040E" to "Luiza S\u0413\u040E", // lost-cp1251
            "S\u0413\u040E & Guarabyra" to "S\u0413\u040E & Guarabyra", // lost-cp1251
            "\u0437\u2014\u0491\u0436\u0458\u045EMrDaft\u0437\u2014\u0491\u0436\u0458\u045E" to "\u0437\u2014\u0491\u0436\u0458\u045EMrDaft\u0437\u2014\u0491\u0436\u0458\u045E", // lost-cp1251
            "\u0432\u201A\u0456\u0412\u00A7\u0431\u2014\u0452M\u041C\u00B6R\u041C\u0456" to "\u0432\u201A\u0456\u0412\u00A7\u0431\u2014\u0452M\u041C\u00B6R\u041C\u0456", // lost-cp1251
            "owl\u043F\u0458\u0409tree" to "owl\u043F\u0458\u0409tree", // lost-cp1251
            "SAZANAMi \u041E\u203Aug." to "SAZANAMi \u041E\u203Aug.", // lost-cp1251
            "Animal \u0420\u201D\u0420\u00B6\u0420\u00B0Z" to "Animal \u0420\u201D\u0420\u00B6\u0420\u00B0Z", // lost-cp1251
            "B\u00CE\u00A3retta X' Rain" to "B\u00CE\u00A3retta X' Rain", // lost-cp1252
            "\u00E3\u201A\u00AB\u00E3\u201A\u00B0\u00E3\u0192\u00A9\u00E3\u0192\u0160\u00E3\u0192\u0160channel\u00EF\u00BC\u008F\u00E3\u0081\u00AA\u00E3\u0081\u00AA\u00E3\u0081\u2039\u00E3\u0081\u0090\u00E3\u201A\u2030" to "\u00E3\u201A\u00AB\u00E3\u201A\u00B0\u00E3\u0192\u00A9\u00E3\u0192\u0160\u00E3\u0192\u0160channel\u00EF\u00BC\u008F\u00E3\u0081\u00AA\u00E3\u0081\u00AA\u00E3\u0081\u2039\u00E3\u0081\u0090\u00E3\u201A\u2030", // lost-cp1252
            "FLORA\u00E3\u0192\u0160\u00E3\u0192\u0081\u00E3\u0192\u00A5\u00E3\u0192\u00A9\u00E3\u0192\u00AB" to "FLORA\u00E3\u0192\u0160\u00E3\u0192\u0081\u00E3\u0192\u00A5\u00E3\u0192\u00A9\u00E3\u0192\u00AB", // lost-cp1252
            "Maria L\u00C3\u0160 Thanh Lan" to "Maria L\u00C3\u0160 Thanh Lan", // lost-cp1252
            "\u00C5\u0152TOMO Yoshihide" to "\u00C5\u0152TOMO Yoshihide", // lost-cp1252
            "END\u00C5\u0152 Ky\u00C5\u008Dko ." to "END\u00C5\u0152 Ky\u00C5\u008Dko .", // lost-cp1252
            "\u00CE\u201D\u00CE\u00B9\u00CE\u00BC\u00CE\u00B9\u00CF\u201E\u00CF\u0081\u00CE\u00B1 \u00CE\u02DC\u00CE\u00B5\u00CE\u00BF\u00CE\u00B4o\u00CF\u0192\u00CE\u00B9\u00CE\u00BF\u00CF\u2026" to "\u00CE\u201D\u00CE\u00B9\u00CE\u00BC\u00CE\u00B9\u00CF\u201E\u00CF\u0081\u00CE\u00B1 \u00CE\u02DC\u00CE\u00B5\u00CE\u00BF\u00CE\u00B4o\u00CF\u0192\u00CE\u00B9\u00CE\u00BF\u00CF\u2026", // lost-cp1252
            "a\u00CF\u2030c" to "a\u00CF\u2030c", // lost-cp1252
            "DJ \u00D0\u00A6\u00D0\u00B2\u00D0\u00B5\u00D1\u201A\u00D0\u00BA\u00D0\u00BEff" to "DJ \u00D0\u00A6\u00D0\u00B2\u00D0\u00B5\u00D1\u201A\u00D0\u00BA\u00D0\u00BEff", // lost-cp1252
            "\u00D0\u2018ALKANSKY" to "\u00D0\u2018ALKANSKY", // lost-cp1252
            "\u041D\u0456" to "\u041D\u0456", // lone-word
            "\u041D\u0456 2" to "\u041D\u0456 2", // lone-word
            "\u041D\u0456 (Live)" to "\u041D\u0456 (Live)", // lone-word
            "\u0422\u0456" to "\u0422\u0456", // lone-word
            "\u0412\u0456" to "\u0412\u0456", // lone-word
            "\u0407\u0457" to "\u0407\u0457", // lone-word
            "\u041D\u0456 \u041D\u0456" to "\u0373 \u0373", // lone-word (repaired)
            "\u0411\u0456-2" to "\u0411\u0456-2", // lone-word
            "M\u0413\u0458ller" to "M\u00FCller", // lone-word (repaired)
            "M\u0102\u013Dller" to "M\u00FCller", // lone-word (repaired)
            "Gr\u0102\u013D\u0102\u017Ae" to "Gr\u00FC\u00DFe", // lone-word (repaired)
            "Gr\u0413\u0458\u0413\u045Fe" to "Gr\u00FC\u00DFe", // lone-word (repaired)
            "\u0420\u0407 2" to "\u0420\u0407 2", // lone-word
            "\u0420\u0407" to "\u0420\u0407", // lone-word
            "\u0107\u0165\u00B1" to "\u0107\u0165\u00B1", // lone-word
            "\u0436\u045C\u00B1" to "\u0436\u045C\u00B1", // lone-word
            "\u0107\u0165\u00B1\u00E4\u015F\u00AC" to "\u6771\u4EAC", // lone-word (repaired)
            "\u0420\u045C\u0421\u2013" to "\u041D\u0456", // lone-word (repaired)
            "\u011B\u017C\u00A4" to "\u011B\u017C\u00A4", // lost-lone-cp1250
            "98\u00C2\u015F" to "98\u00C2\u015F", // lost-lone-cp1250
            "Mads \u0102\u0098" to "Mads \u0102\u0098", // lost-lone-cp1250
            "\u0111\u017A\u0090\u015F" to "\u0111\u017A\u0090\u015F", // lost-lone-cp1250
            "08\u00E9\u02D8\u00A8" to "08\u00E9\u02D8\u00A8", // lost-lone-cp1250
            "\u0436\u0490\u00AD-karma-" to "\u0436\u0490\u00AD-karma-", // lost-lone-cp1251
            "The Show \u0432\u0402\u201C A Tribute to ABBA" to "The Show \u0432\u0402\u201C A Tribute to ABBA", // lost-lone-cp1251
            "\u0435\u045C\u0409" to "\u0435\u045C\u0409", // lost-lone-cp1251
            "\u0436\u0407\u2026" to "\u0436\u0407\u2026", // lost-lone-cp1251
            "\u0437\u0457\u00A0" to "\u0437\u0457\u00A0", // lost-lone-cp1251
        )
    }
}
