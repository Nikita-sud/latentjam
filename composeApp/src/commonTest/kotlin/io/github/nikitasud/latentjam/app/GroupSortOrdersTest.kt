/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.SongSortDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupSortOrdersTest {
    @Test fun newestDateGenresAppearFirstAndUntaggedStayLast() {
        val groups = listOf("2023-01", "2025-04", "2026-10", "", null)
        assertEquals(listOf("2026-10", "2025-04", "2023-01", "", null),
            reverseNamedGroups(groups, SongSortDirection.DESCENDING) { it })
        assertEquals(groups, reverseNamedGroups(groups, SongSortDirection.ASCENDING) { it })
    }

    @Test fun eachPageRetainsItsOwnOrderAndDamagedPreferencesUseDefaults() {
        val order = GroupSortOrders(genres = SongSortDirection.DESCENDING)
        assertEquals(order, groupSortOrdersFromPersisted(encodeGroupSortOrders(order)))
        for (value in listOf(null, "", "DESCENDING", "ASCENDING,invalid,DESCENDING")) {
            assertEquals(GroupSortOrders(), groupSortOrdersFromPersisted(value))
        }
    }
}
