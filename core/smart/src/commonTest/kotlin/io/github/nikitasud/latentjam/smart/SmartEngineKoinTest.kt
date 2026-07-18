/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.di.smartEngineDispatcherQualifier
import io.github.nikitasud.latentjam.smart.di.smartEngineModule
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

internal class SmartEngineKoinTest {

    // The real platform backend (ONNX on Android) needs an android.content.Context
    // in the graph, so it is exercised live on device rather than here; this
    // test proves the common graph shape with the backend binding overridden,
    // exactly how app tests will consume the module.
    @Test
    fun engineIsASingletonAndGraphResolvesWithTestOverrides() {
        val app = koinApplication {
            modules(
                smartEngineModule,
                // The backend binding normally comes from smartEngineBackendModule();
                // bind the fake directly plus a shrunken-dim config.
                module {
                    single { SmartEngineConfig(embeddingDim = 3) }
                    single<EmbeddingBackend> { FakeEmbeddingBackend() }
                },
            )
        }
        try {
            val koin = app.koin
            assertSame(koin.get<SimilarityEngine>(), koin.get<SimilarityEngine>())
            assertIs<FakeEmbeddingBackend>(koin.get<EmbeddingBackend>())
            assertIs<InMemoryVectorIndex>(koin.get<VectorIndex>())
            assertNotNull(koin.get<CoroutineDispatcher>(smartEngineDispatcherQualifier))
            assertEquals(3, koin.get<SmartEngineConfig>().embeddingDim)
        } finally {
            app.close()
        }
    }
}
