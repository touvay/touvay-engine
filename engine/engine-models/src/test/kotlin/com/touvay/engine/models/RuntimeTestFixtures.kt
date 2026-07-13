package com.touvay.engine.models

import com.touvay.runtime.api.DeviceProfile
import com.touvay.runtime.api.InferenceRuntime
import com.touvay.runtime.api.InferenceSession
import com.touvay.runtime.api.LoadConfig
import com.touvay.runtime.api.ModelInstance
import com.touvay.runtime.api.ModelInstanceInfo
import com.touvay.runtime.api.ResolvedModelPack
import com.touvay.runtime.api.RuntimeAvailability
import com.touvay.runtime.api.RuntimeId
import com.touvay.runtime.api.SessionConfig
import com.touvay.runtime.api.TokenSequence
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object RuntimeTestFixtures {
    val device: DeviceProfile = DeviceProfile(
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        isLowRamDevice = false,
        supportedAbis = listOf("x86_64"),
    )

    fun registry(runtime: FakeRuntime): DefaultRuntimeRegistry = DefaultRuntimeRegistry(
        listOf(
            RuntimeBinding(
                identity = "llamacpp.b5199",
                runtime = runtime,
                adapterVersion = "1.0.0",
                features = setOf("tokenize"),
            ),
        ),
    )
}

internal class FakeRuntime(
    override val id: RuntimeId = RuntimeId("llamacpp"),
) : InferenceRuntime {
    val probeCalls = AtomicInteger()
    val loadCalls = AtomicInteger()
    val loadedPacks = mutableListOf<ResolvedModelPack>()
    val loadConfigs = mutableListOf<LoadConfig>()
    @Volatile var availability: RuntimeAvailability = RuntimeAvailability.Available(setOf("cpu"))
    @Volatile var probeFailure: Throwable? = null
    @Volatile var loadFailure: Throwable? = null
    @Volatile var instanceFactory: () -> FakeModelInstance = { FakeModelInstance() }
    @Volatile var beforeLoad: () -> Unit = { }

    override fun probe(device: DeviceProfile): RuntimeAvailability {
        probeCalls.incrementAndGet()
        probeFailure?.let { throw it }
        return availability
    }

    override fun loadModel(pack: ResolvedModelPack, config: LoadConfig): ModelInstance {
        loadCalls.incrementAndGet()
        synchronized(loadedPacks) {
            loadedPacks += pack
            loadConfigs += config
        }
        beforeLoad()
        loadFailure?.let { throw it }
        return instanceFactory()
    }
}

internal class FakeModelInstance(
    override val info: ModelInstanceInfo = ModelInstanceInfo(
        estimatedRamBytes = 512L * 1024 * 1024,
        maxContextLength = 1024,
    ),
    private val closeAction: () -> Unit = { },
) : ModelInstance {
    private val closed = AtomicBoolean(false)
    val closeCalls = AtomicInteger()
    val sessionCalls = AtomicInteger()

    override fun createSession(config: SessionConfig): InferenceSession {
        sessionCalls.incrementAndGet()
        error("session creation is outside Slice 4")
    }

    override fun tokenize(text: String): TokenSequence {
        check(!closed.get())
        return TokenSequence(IntArray(0))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeCalls.incrementAndGet()
            closeAction()
        }
    }
}
