/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/** Commit a drop against the same order that was displayed, preserving current visibility. */
internal fun PageLayout.reorderPageAfterDrop(startingOrder: List<StartPage>, from: Int, to: Int): PageLayout {
    val current = normalized()
    if (current.order != startingOrder || from !in current.order.indices || to !in current.order.indices) return this
    return current.movePage(current.order[from], to - from)
}
