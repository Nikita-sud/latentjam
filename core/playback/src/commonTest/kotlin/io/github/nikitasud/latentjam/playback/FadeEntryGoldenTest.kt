/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fade-start rules against the research implementation they were ported from: 160 level curves
 * from a small deterministic generator the research script runs too, and the answers it gave.
 */
class FadeEntryGoldenTest {
    private class Lcg(seed: Long) {
        private var x = seed and 0x7FFFFFFFL
        fun u(): Double {
            x = (x * 1103515245L + 12345L) and 0x7FFFFFFFL
            return x / 2147483648.0
        }
        fun between(a: Double, b: Double): Double = a + (b - a) * u()
        fun noise(sd: Double): Double = (u() + u() + u() + u() - 2.0) * sd * 1.7320508075688772
    }

    private fun curve(case: Int): Pair<FloatArray, Double> {
        val g = Lcg(20261102L + case * 7919L)
        val fam = case % 8
        val n = g.between(700.0, 1200.0).toInt()
        val level = g.between(-20.0, -8.0)
        val sd = g.between(0.3, 1.5)
        val last = 0.2 + 0.1 * (n - 1)
        val f = last - g.between(6.0, 40.0)
        val length = last - f
        val depth = g.between(20.0, 60.0)
        val y = FloatArray(n) { i ->
            val ti = 0.2 + 0.1 * i
            var v = level
            if (fam == 0 && ti > f) v = level - depth * (ti - f) / length
            if (fam == 1 && ti > f) v = level + 20 * log10(max(1 - (ti - f) / length, 1e-3))
            if (fam == 2 && ti > f) {
                v = level - 6 * min(1.0, (ti - f) / 2) - (depth - 6) * max(0.0, ti - f - 2) / max(length - 2, 1.0)
            }
            if (fam == 3) {
                if (f - 12 < ti && ti < f - 6) v = level - 8
                if (ti > f) v = level - depth * (ti - f) / length
            }
            if (fam == 4) {
                if (f < ti && ti < f + length / 3) v = level - 2 * (ti - f)
                if (ti > last - 3) v = level - 30
            }
            if (fam == 6 && ti > f) v = max(level - depth * (ti - f) / (length / 2), level - depth)
            if (fam == 7 && ti > f) v = level - 10
            (v + g.noise(sd)).toFloat()
        }
        return y to last
    }

    @Test
    fun matchesTheResearchRuleOnEveryGoldenCurve() {
        val mismatches = mutableListOf<String>()
        for (line in GOLDEN.trim().lines()) {
            val (case, rule, why, start) = line.split(' ')
            val (rms, last) = curve(case.toInt())
            val got = fadeStart(rms, 0.0, last, if (rule == "knee") FadeEntryRule.KNEE else FadeEntryRule.VISIBLE)
            val expected = REFUSALS.getValue(why)
            val ok = got.refusal == expected &&
                (expected != FadeRefusal.NONE || abs(got.startS!! - start.toDouble()) < 1e-6)
            if (!ok) mismatches += "$line -> ${got.refusal} ${got.startS}"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun bothReadingsAndRefusalsAreCovered() {
        val whys = GOLDEN.trim().lines().map { it.split(' ')[2] }.toSet()
        assertTrue("ok" in whys && "G3" in whys && "no_decline" in whys, whys.toString())
    }

    private companion object {
        val REFUSALS = mapOf(
            "ok" to FadeRefusal.NONE, "short" to FadeRefusal.SHORT, "window" to FadeRefusal.WINDOW,
            "no_decline" to FadeRefusal.NO_DECLINE, "G1" to FadeRefusal.RECOVERS, "G2" to FadeRefusal.COMES_BACK,
            "G3" to FadeRefusal.SHALLOW,
        )

        /** `python -I fd2_app_golden.py` in the research repository (case, rule, refusal, start s). */
        val GOLDEN = """
0 knee ok 92.9
0 nr3 ok 93.9
1 knee ok 81.9
1 nr3 ok 87.4
2 knee ok 41.10000000000001
2 nr3 ok 42.10000000000001
3 knee ok 58.7
3 nr3 ok 60.2
4 knee ok 104.60000000000001
4 nr3 ok 105.60000000000001
5 knee no_decline -
5 nr3 no_decline -
6 knee ok 54.400000000000006
6 nr3 ok 55.400000000000006
7 knee G3 -
7 nr3 G3 -
8 knee ok 87.60000000000001
8 nr3 ok 89.60000000000001
9 knee ok 57.10000000000001
9 nr3 ok 61.60000000000001
10 knee ok 68.2
10 nr3 ok 69.2
11 knee ok 85.2
11 nr3 ok 86.7
12 knee ok 71.4
12 nr3 ok 71.9
13 knee G3 -
13 nr3 G3 -
14 knee ok 81.9
14 nr3 ok 82.4
15 knee G3 -
15 nr3 G3 -
16 knee ok 64.2
16 nr3 ok 65.7
17 knee ok 82.2
17 nr3 ok 85.2
18 knee ok 95.4
18 nr3 ok 96.9
19 knee ok 62.00000000000001
19 nr3 ok 63.00000000000001
20 knee ok 88.00000000000001
20 nr3 ok 88.50000000000001
21 knee G3 -
21 nr3 G3 -
22 knee ok 59.2
22 nr3 ok 59.7
23 knee G3 -
23 nr3 G3 -
24 knee ok 90.9
24 nr3 ok 91.9
25 knee ok 107.9
25 nr3 ok 108.9
26 knee ok 74.0
26 nr3 ok 74.0
27 knee ok 56.60000000000001
27 nr3 ok 57.60000000000001
28 knee ok 105.10000000000001
28 nr3 ok 105.60000000000001
29 knee G3 -
29 nr3 G3 -
30 knee ok 52.300000000000004
30 nr3 ok 53.300000000000004
31 knee ok 67.9
31 nr3 ok 68.4
32 knee ok 85.4
32 nr3 ok 87.4
33 knee ok 55.10000000000001
33 nr3 ok 60.60000000000001
34 knee ok 66.2
34 nr3 ok 67.7
35 knee ok 82.7
35 nr3 ok 84.2
36 knee ok 71.30000000000001
36 nr3 ok 71.80000000000001
37 knee G3 -
37 nr3 G3 -
38 knee ok 80.4
38 nr3 ok 81.4
39 knee ok 95.60000000000001
39 nr3 ok 96.10000000000001
40 knee ok 61.7
40 nr3 ok 63.2
41 knee ok 81.2
41 nr3 ok 83.7
42 knee ok 93.7
42 nr3 ok 94.7
43 knee ok 59.900000000000006
43 nr3 ok 60.900000000000006
44 knee ok 88.00000000000001
44 nr3 ok 88.50000000000001
45 knee no_decline -
45 nr3 no_decline -
46 knee ok 57.00000000000001
46 nr3 ok 58.00000000000001
47 knee ok 72.7
47 nr3 ok 73.2
48 knee ok 89.2
48 nr3 ok 90.2
49 knee ok 105.9
49 nr3 ok 107.9
50 knee ok 71.5
50 nr3 ok 72.0
51 knee ok 87.10000000000001
51 nr3 ok 88.10000000000001
52 knee ok 104.60000000000001
52 nr3 ok 105.10000000000001
53 knee G3 -
53 nr3 G3 -
54 knee ok 50.800000000000004
54 nr3 ok 52.300000000000004
55 knee ok 65.9
55 nr3 ok 66.9
56 knee ok 82.9
56 nr3 ok 84.4
57 knee ok 53.00000000000001
57 nr3 ok 58.00000000000001
58 knee ok 64.60000000000001
58 nr3 ok 65.60000000000001
59 knee ok 81.60000000000001
59 nr3 ok 84.60000000000001
60 knee ok 71.30000000000001
60 nr3 ok 71.80000000000001
61 knee G3 -
61 nr3 G3 -
62 knee ok 77.9
62 nr3 ok 78.9
63 knee ok 93.4
63 nr3 ok 93.9
64 knee ok 60.60000000000001
64 nr3 ok 63.60000000000001
65 knee ok 79.2
65 nr3 ok 82.2
66 knee ok 92.2
66 nr3 ok 92.7
67 knee ok 58.900000000000006
67 nr3 ok 59.900000000000006
68 knee ok 87.9
68 nr3 ok 88.4
69 knee G3 -
69 nr3 G3 -
70 knee ok 55.00000000000001
70 nr3 ok 56.00000000000001
71 knee G3 -
71 nr3 G3 -
72 knee ok 87.7
72 nr3 ok 88.7
73 knee ok 104.7
73 nr3 ok 106.7
74 knee ok 69.4
74 nr3 ok 70.4
75 knee ok 84.9
75 nr3 ok 85.9
76 knee ok 104.60000000000001
76 nr3 ok 105.10000000000001
77 knee G3 -
77 nr3 G3 -
78 knee ok 49.2
78 nr3 ok 50.2
79 knee ok 64.2
79 nr3 ok 64.7
80 knee ok 81.4
80 nr3 ok 84.4
81 knee ok 52.00000000000001
81 nr3 ok 58.00000000000001
82 knee ok 62.10000000000001
82 nr3 ok 63.60000000000001
83 knee ok 79.60000000000001
83 nr3 ok 81.10000000000001
84 knee ok 71.2
84 nr3 ok 71.7
85 knee G3 -
85 nr3 G3 -
86 knee ok 76.4
86 nr3 ok 76.9
87 knee G3 -
87 nr3 G3 -
88 knee ok 58.60000000000001
88 nr3 ok 60.10000000000001
89 knee ok 77.60000000000001
89 nr3 ok 81.60000000000001
90 knee ok 89.60000000000001
90 nr3 ok 91.10000000000001
91 knee ok 56.300000000000004
91 nr3 ok 57.300000000000004
92 knee ok 87.9
92 nr3 ok 88.4
93 knee no_decline -
93 nr3 no_decline -
94 knee ok 53.50000000000001
94 nr3 ok 55.00000000000001
95 knee G3 -
95 nr3 G3 -
96 knee ok 85.60000000000001
96 nr3 ok 86.60000000000001
97 knee ok 102.7
97 nr3 ok 105.2
98 knee ok 66.9
98 nr3 ok 68.4
99 knee ok 83.4
99 nr3 ok 83.9
100 knee ok 104.4
100 nr3 ok 105.4
101 knee no_decline -
101 nr3 no_decline -
102 knee ok 80.7
102 nr3 ok 81.2
103 knee G3 -
103 nr3 G3 -
104 knee ok 79.4
104 nr3 ok 81.4
105 knee ok 50.400000000000006
105 nr3 ok 57.400000000000006
106 knee ok 60.900000000000006
106 nr3 ok 61.900000000000006
107 knee ok 77.60000000000001
107 nr3 ok 78.60000000000001
108 knee ok 71.2
108 nr3 ok 71.7
109 knee G3 -
109 nr3 G3 -
110 knee ok 74.9
110 nr3 ok 75.4
111 knee G3 -
111 nr3 G3 -
112 knee ok 56.50000000000001
112 nr3 ok 57.50000000000001
113 knee ok 76.60000000000001
113 nr3 ok 79.10000000000001
114 knee ok 88.10000000000001
114 nr3 ok 89.10000000000001
115 knee ok 55.800000000000004
115 nr3 ok 57.300000000000004
116 knee ok 87.80000000000001
116 nr3 ok 88.30000000000001
117 knee G3 -
117 nr3 G3 -
118 knee ok 51.50000000000001
118 nr3 ok 52.50000000000001
119 knee ok 67.10000000000001
119 nr3 ok 67.60000000000001
120 knee ok 83.60000000000001
120 nr3 ok 84.60000000000001
121 knee ok 101.60000000000001
121 nr3 ok 104.60000000000001
122 knee ok 65.30000000000001
122 nr3 ok 66.30000000000001
123 knee ok 82.4
123 nr3 ok 82.9
124 knee ok 104.9
124 nr3 ok 105.4
125 knee G3 -
125 nr3 G3 -
126 knee ok 78.7
126 nr3 ok 79.7
127 knee ok 94.60000000000001
127 nr3 ok 95.10000000000001
128 knee ok 79.7
128 nr3 ok 82.2
129 knee ok 48.900000000000006
129 nr3 ok 54.400000000000006
130 knee ok 58.900000000000006
130 nr3 ok 59.400000000000006
131 knee ok 76.60000000000001
131 nr3 ok 78.10000000000001
132 knee ok 71.5
132 nr3 ok 72.0
133 knee G3 -
133 nr3 G3 -
134 knee ok 72.7
134 nr3 ok 73.7
135 knee ok 87.9
135 nr3 ok 88.4
136 knee ok 55.50000000000001
136 nr3 ok 58.00000000000001
137 knee ok 73.9
137 nr3 ok 78.4
138 knee ok 86.60000000000001
138 nr3 ok 87.10000000000001
139 knee ok 53.2
139 nr3 ok 54.7
140 knee ok 87.80000000000001
140 nr3 ok 88.30000000000001
141 knee G3 -
141 nr3 G3 -
142 knee ok 49.50000000000001
142 nr3 ok 50.50000000000001
143 knee ok 65.0
143 nr3 ok 65.5
144 knee ok 82.10000000000001
144 nr3 ok 83.10000000000001
145 knee ok 100.10000000000001
145 nr3 ok 102.60000000000001
146 knee ok 63.300000000000004
146 nr3 ok 64.80000000000001
147 knee ok 79.9
147 nr3 ok 80.9
148 knee ok 104.4
148 nr3 ok 104.9
149 knee G3 -
149 nr3 G3 -
150 knee ok 77.10000000000001
150 nr3 ok 77.60000000000001
151 knee G3 -
151 nr3 G3 -
152 knee ok 108.7
152 nr3 ok 109.7
153 knee ok 75.80000000000001
153 nr3 ok 76.30000000000001
154 knee ok 56.900000000000006
154 nr3 ok 57.900000000000006
155 knee ok 73.9
155 nr3 ok 75.4
156 knee ok 71.5
156 nr3 ok 72.0
157 knee G3 -
157 nr3 G3 -
158 knee ok 71.2
158 nr3 ok 73.2
159 knee G3 -
159 nr3 G3 -
"""
    }
}
