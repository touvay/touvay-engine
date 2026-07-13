package com.touvay.engine.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeCacheConsistencyTest {
    private val identity = ModelRevisionIdentity("pack", "1.0.0", "a".repeat(64))
    private val profile = ExecutionProfile("binding", 2, true)
    private val key = InstanceKey(identity, profile)
    private val verifier = RuntimeCacheConsistencyVerifier()

    @Test
    fun `valid lifecycle states satisfy reference invariants`() {
        RuntimeInstanceState.entries.forEach { state ->
            val references = if (state == RuntimeInstanceState.ACTIVE) 1 else 0
            verifier.verify(
                RuntimeCacheSnapshot(listOf(RuntimeCacheEntrySnapshot(key, state, references))),
            )
        }
    }

    @Test
    fun `duplicate keys fail consistency verification`() {
        val failure = assertFailsWith<RuntimeLifecycleException> {
            verifier.verify(
                RuntimeCacheSnapshot(
                    listOf(
                        RuntimeCacheEntrySnapshot(key, RuntimeInstanceState.READY_IDLE, 0),
                        RuntimeCacheEntrySnapshot(key, RuntimeInstanceState.UNLOADING, 0),
                    ),
                ),
            )
        }
        assertEquals(RuntimeLifecycleFailure.CACHE_INCONSISTENT, failure.failure)
    }

    @Test
    fun `state and reference mismatches fail consistency verification`() {
        listOf(
            RuntimeCacheEntrySnapshot(key, RuntimeInstanceState.READY_IDLE, 1),
            RuntimeCacheEntrySnapshot(key, RuntimeInstanceState.ACTIVE, 0),
            RuntimeCacheEntrySnapshot(key, RuntimeInstanceState.UNLOADING, 1),
            RuntimeCacheEntrySnapshot(key, RuntimeInstanceState.LOADING, -1),
        ).forEach { invalid ->
            val failure = assertFailsWith<RuntimeLifecycleException> {
                verifier.verify(RuntimeCacheSnapshot(listOf(invalid)))
            }
            assertEquals(RuntimeLifecycleFailure.CACHE_INCONSISTENT, failure.failure)
        }
    }
}
