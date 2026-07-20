/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS [IndexingNotifier] — intentionally silent for now.
 *
 * There is nothing to report yet: the audio encoder is still a stub on iOS, so
 * analysis never runs. When it does, this is where a `UNUserNotificationCenter`
 * progress notification goes — and it will need an authorisation prompt first,
 * which is a user-facing decision rather than a drop-in.
 */
internal class SilentIndexingNotifier : IndexingNotifier {
    override fun show(title: String, text: String, done: Int, total: Int) = Unit
    override fun finish() = Unit
}

actual fun indexingNotifierModule(): Module = module {
    single<IndexingNotifier> { SilentIndexingNotifier() }
}
