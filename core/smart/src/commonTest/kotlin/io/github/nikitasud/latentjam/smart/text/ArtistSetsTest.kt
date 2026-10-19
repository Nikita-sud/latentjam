/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The artist-set port's hashed hard cases and normalisation strings in common code, so they also run on Kotlin/Native
 * (generated from artist_sets_golden.tsv.gz; ArtistSetsGoldenTest checks the full fixture on the JVM).
 */
class ArtistSetsTest {
    private val testList: Map<Long, Long> = mapOf(
        7019073566203326606L to 7019073566203326606L,
        -1798134611533832747L to -1798134611533832747L,
        3999295094319791569L to 3999295094319791569L,
        -7693354083231453965L to -7693354083231453965L,
        -1463854950202570253L to 6178069566779324811L,
        5073007475158952283L to 5073007475158952283L,
        -3797678650956577730L to -3797678650956577730L,
        -5439193733841740778L to -5439193733841740778L,
        9109724916601673998L to 9109724916601673998L,
        -2198166631464798825L to -2198166631464798825L,
        -4941998836500104237L to -4941998836500104237L,
        5989408081467738512L to 5989408081467738512L,
        -9168985443799310664L to -9168985443799310664L,
        4967729857902701276L to 4967729857902701276L,
        9142670440663789687L to 9142670440663789687L,
        7597251293204808745L to 7597251293204808745L,
        -4392430098596626875L to -4392430098596626875L,
        -6163187665263934279L to -6163187665263934279L,
        -768235283424247787L to -768235283424247787L,
        -9083207352910962926L to 412439746740640487L,
        412439746740640487L to 412439746740640487L,
        1132184572053978950L to -7693354083231453965L,
        -2181615040106935092L to -2181615040106935092L,
        -6410075790589849390L to 6178069566779324811L,
        -5084174944492550053L to -5084174944492550053L,
        471126247271874909L to 471126247271874909L,
        6178069566779324811L to 6178069566779324811L,
        -5219207450189037831L to -5219207450189037831L,
        -497340154734848524L to -497340154734848524L,
        4058121271191780974L to 4058121271191780974L,
        -5328744896638209788L to -5328744896638209788L,
        -7429641156348670276L to -7429641156348670276L,
        3754776839060513202L to 3754776839060513202L,
        -4295362318938199357L to -4295362318938199357L,
        246332665784127251L to 246332665784127251L,
        -5838239674086825817L to -5838239674086825817L,
        -6042480338621390271L to -6042480338621390271L,
        1329680434046241565L to 1329680434046241565L,
        7410355728291633008L to 7410355728291633008L,
        7736807780483420806L to 7736807780483420806L,
        -3974905031166986287L to -3974905031166986287L,
        4459164487991873854L to 4459164487991873854L,
        -2021060215432114976L to -2021060215432114976L,
        -6476159813001616121L to -6476159813001616121L,
        -5808529385363204345L to -5808529385363204345L,
        -5513694604488322565L to -5513694604488322565L,
        7112979135032352347L to 7112979135032352347L,
        -8238788883194938525L to -8238788883194938525L,
        -6585450384511923327L to -6585450384511923327L,
        6549458931158836954L to 6549458931158836954L,
        -2475420808041675076L to -2475420808041675076L
    )

    private val cases: List<Triple<Pair<String, String>, List<Long>, List<Long>>> = listOf(
        Triple("Earth, Wind & Fire" to "", listOf(7597251293204808745L), listOf()),
        Triple("Tyler, The Creator" to "", listOf(4459164487991873854L), listOf()),
        Triple("Crosby, Stills, Nash & Young" to "", listOf(-9168985443799310664L), listOf()),
        Triple("Florence + the Machine" to "", listOf(412439746740640487L), listOf()),
        Triple("Florence and the Machine" to "", listOf(412439746740640487L), listOf()),
        Triple("Simon & Garfunkel" to "", listOf(7410355728291633008L), listOf()),
        Triple("Malcolm X" to "", listOf(-7429641156348670276L), listOf()),
        Triple("X Japan" to "", listOf(-5513694604488322565L), listOf()),
        Triple("X-Japan" to "", listOf(-5513694604488322565L), listOf()),
        Triple("Brand X" to "", listOf(9109724916601673998L), listOf()),
        Triple("Usher feat. Lil Jon & Ludacris" to "", listOf(-2021060215432114976L, -5219207450189037831L, -497340154734848524L), listOf(-5219207450189037831L, -497340154734848524L)),
        Triple("Usher ft. Lil Jon" to "", listOf(-2021060215432114976L, -5219207450189037831L), listOf(-5219207450189037831L)),
        Triple("Usher Featuring Unknown Guy" to "", listOf(-2021060215432114976L, 8072545739516788478L), listOf(8072545739516788478L)),
        Triple("Usher (feat. Ludacris)" to "", listOf(-2021060215432114976L, -497340154734848524L), listOf(-497340154734848524L)),
        Triple("Ado ft.Eve" to "", listOf(-1798134611533832747L, -4392430098596626875L), listOf(-4392430098596626875L)),
        Triple("Daft Punk" to "Get Lucky (feat. Pharrell Williams)", listOf(4967729857902701276L, -5838239674086825817L), listOf(-5838239674086825817L)),
        Triple("Daft Punk" to "Get Lucky [ft. Pharrell Williams & Usher]", listOf(4967729857902701276L, -5838239674086825817L, -2021060215432114976L), listOf(-5838239674086825817L, -2021060215432114976L)),
        Triple("Daft Punk feat. Pharrell Williams" to "Get Lucky (feat. Pharrell Williams)", listOf(4967729857902701276L, -5838239674086825817L), listOf(-5838239674086825817L)),
        Triple("YOASOBI × Ado" to "", listOf(7112979135032352347L, -1798134611533832747L), listOf()),
        Triple("米津玄師、Ado" to "", listOf(-2475420808041675076L, -1798134611533832747L), listOf()),
        Triple("YOASOBI／Ado" to "", listOf(7112979135032352347L, -1798134611533832747L), listOf()),
        Triple("YOASOBI＆Ado" to "", listOf(7112979135032352347L, -1798134611533832747L), listOf()),
        Triple("Beyoncé & JAY-Z" to "", listOf(-3797678650956577730L, 471126247271874909L), listOf()),
        Triple("Beyonce with Jay Z" to "", listOf(-3797678650956577730L, 471126247271874909L), listOf()),
        Triple("Usher vs. Ludacris" to "", listOf(-2021060215432114976L, -497340154734848524L), listOf()),
        Triple("Usher x Ludacris" to "", listOf(-2021060215432114976L, -497340154734848524L), listOf()),
        Triple("Usher, Lil Jon; Ludacris" to "", listOf(-2021060215432114976L, -5219207450189037831L, -497340154734848524L), listOf()),
        Triple("Earth, Wind & Fire, Usher" to "", listOf(7597251293204808745L, -2021060215432114976L), listOf()),
        Triple("Simon & Somebody Nobody" to "", listOf(7592631557708206578L), listOf()),
        Triple("Usher x Nobody Known" to "", listOf(8993155830816260244L), listOf()),
        Triple("AC/DC" to "", listOf(7019073566203326606L), listOf()),
        Triple("Some Duo / Other" to "", listOf(-3130373461144502013L), listOf()),
        Triple("Various Artists" to "", listOf(), listOf()),
        Triple("[unknown]" to "", listOf(), listOf()),
        Triple("[traditional]" to "", listOf(), listOf()),
        Triple("Anonymous" to "", listOf(), listOf()),
        Triple("Anonymous Band" to "", listOf(3999295094319791569L), listOf()),
        Triple("Various Artists feat. Usher" to "", listOf(-2021060215432114976L), listOf(-2021060215432114976L)),
        Triple("" to "", listOf(), listOf()),
        Triple("The Beatles" to "", listOf(5073007475158952283L), listOf()),
        Triple("Beatles" to "", listOf(5073007475158952283L), listOf()),
        Triple("THE BEATLES" to "", listOf(5073007475158952283L), listOf()),
        Triple("Sigur Ros" to "", listOf(-6042480338621390271L), listOf()),
        Triple("Motley Crue" to "", listOf(3754776839060513202L), listOf()),
        Triple("Елка" to "", listOf(-6585450384511923327L), listOf()),
        Triple("КИНО" to "", listOf(6549458931158836954L), listOf()),
        Triple("J.S. Bach" to "", listOf(6178069566779324811L), listOf())
    )

    private val norms: List<Pair<String, String>> = listOf(
        "Beyoncé" to "beyonce",
        "Ёлка" to "елка",
        "Mötley Crüe" to "motley crue",
        "Sigur Rós" to "sigur ros",
        "The Beatles" to "beatles",
        "the" to "the",
        "The The" to "the",
        "米津玄師" to "米津玄師",
        "ＹＯＡＳＯＢＩ" to "yoasobi",
        "Fairuz فيروز" to "fairuz فيروز",
        "İzel" to "izel",
        "DIŞ" to "dis",
        "Ölüm" to "olum",
        "Straße" to "strasse",
        "Æon" to "aeon",
        "Øystein" to "oystein",
        "Łódź" to "lodz",
        "Đorđe" to "dorde",
        "Þór" to "thor",
        "AC/DC" to "ac dc",
        "Jay‐Z" to "jay z",
        "Ninho 🎵" to "ninho",
        "B̈jörk" to "bjork",
        "Kendrick  Lamar\t" to "kendrick lamar",
        "हनी सिंह" to "हनी सिंह",
        "Ψυχή" to "ψυχη",
        "ΣΟΦΟΣ" to "σοφος",
        "Rǫnd" to "rond",
        "𠀀𠀁 artist" to "𠀀𠀁 artist",
        "ℌ𝔢𝔩𝔩𝔬" to "hello",
        "①②" to "12"
    )

    @Test
    fun hardCasesMatchTheReference() {
        for ((input, artists, featured) in cases) {
            val set = ArtistSets.parse(input.first, input.second, { testList[it] })
            assertEquals(artists, set.artists, "'${input.first}' / '${input.second}'")
            assertEquals(featured.toSet(), set.featured, "featured '${input.first}'")
        }
    }

    @Test
    fun normalizeMatchesTheReference() {
        for ((raw, want) in norms) assertEquals(want, ArtistSets.normalize(raw), "normalize '$raw'")
    }
}
