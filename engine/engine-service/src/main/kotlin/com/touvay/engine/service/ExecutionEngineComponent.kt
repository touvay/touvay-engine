package com.touvay.engine.service

import com.touvay.engine.core.ExecutionCoordinator
import com.touvay.engine.core.ExecutionProgramRegistry
import com.touvay.engine.core.ExecutionRouter
import com.touvay.engine.core.PriorityExecutionScheduler
import com.touvay.engine.core.SchedulerLimits
import com.touvay.engine.models.RuntimeInstanceManager
import com.touvay.runtime.api.DeviceProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Milestone 5 composition boundary. It connects execution-core to the existing Model
 * Manager without registering a user-facing capability or changing Runtime SPI.
 */
internal class ExecutionEngineComponent(
    modelManager: RuntimeInstanceManager,
    deviceProfile: () -> DeviceProfile,
    programs: ExecutionProgramRegistry = ProductionCapabilityComposition.executionPrograms,
    router: ExecutionRouter,
    limits: SchedulerLimits = SchedulerLimits.DEFAULT,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val modelProvider = RuntimeInstanceExecutionModelProvider(modelManager, deviceProfile)
    private val scheduler = PriorityExecutionScheduler(limits)

    val coordinator: ExecutionCoordinator = ExecutionCoordinator(
        programs = programs,
        router = router,
        scheduler = scheduler,
        models = modelProvider,
        dispatcher = dispatcher,
    )

    suspend fun shutdown() {
        coordinator.shutdownAndAwait()
        modelProvider.close()
    }
}
