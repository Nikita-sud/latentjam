/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.di.smartEngineDispatcherQualifier
import io.github.nikitasud.latentjam.smart.di.smartEngineModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class SmartEngineKoinTest {

    @Test
    fun engineIsASingletonAndGraphResolvesWithTestOverrides() {
        val app = koinApplication {
            modules(
                smartEngineModule,
                // Last-definition-wins override (Koin default): shrink the dim
                // and swap the platform backend for the fake.
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

    @Test
    fun defaultModuleWiresTheRealPlatformStub() = runTest {
        // No overrides: exercises the expect/actual factory on every target
        // (AndroidEmbeddingBackend on the JVM/Android run, IosEmbeddingBackend
        // on the iOS simulator run) and the stub's graceful-degradation contract.
        val app = koinApplication { modules(smartEngineModule) }
        try {
            val engine = app.koin.get<SimilarityEngine>()
            val result = engine.initialize()
            assertTrue(result.isFailure, "Platform stubs must report ModelUnavailable")
            val exception = assertIs<SmartEngineException>(result.exceptionOrNull())
            assertEquals(EngineError.ModelUnavailable, exception.error)
            assertEquals(EngineState.Failed(EngineError.ModelUnavailable), engine.state.value)
        } finally {
            app.close()
        }
    }
}
