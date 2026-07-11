package com.touvay.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.touvay.contract.ClientHello
import com.touvay.contract.EngineHello
import com.touvay.contract.ITouvayEngine
import com.touvay.contract.TouvayContract
import com.touvay.sdk.internal.TouvayClientImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Entry point of the Touvay SDK. */
public object Touvay {

    internal const val SDK_VERSION_NAME: String = "0.1.0"

    /**
     * Binds to the Touvay Engine and negotiates the contract version.
     *
     * Embedded phase: binds the engine service inside the calling app's own package.
     * Discovery of a shared standalone engine app is a future, additive step behind this
     * same call (ARCHITECTURE.md ADR-001) — client code will not change.
     *
     * Main-safe; suspends until connected. Cancelling the calling coroutine releases the
     * binding.
     *
     * @throws TouvayException.EngineUnavailable when no engine service can be bound.
     * @throws TouvayException.EngineIncompatible when contract versions don't overlap.
     * @throws TouvayException.EngineDisconnected when the engine dies mid-handshake.
     */
    public suspend fun connect(context: Context): TouvayClient {
        val appContext = context.applicationContext
        val intent = Intent(TouvayContract.ACTION_BIND_ENGINE).setPackage(appContext.packageName)
        val awaiter = BindAwaiter()

        val bound = try {
            appContext.bindService(intent, awaiter, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            throw TouvayException.EngineUnavailable("not allowed to bind the engine service", e)
        }
        if (!bound) {
            runCatching { appContext.unbindService(awaiter) }
            throw TouvayException.EngineUnavailable(
                "no engine service found in package ${appContext.packageName}",
            )
        }

        val engine = try {
            awaiter.engine.await()
        } catch (e: CancellationException) {
            runCatching { appContext.unbindService(awaiter) }
            throw e
        } catch (e: TouvayException) {
            runCatching { appContext.unbindService(awaiter) }
            throw e
        }

        val hello = try {
            // Binder calls are synchronous IPC: never on the caller's (possibly main) thread.
            withContext(Dispatchers.IO) {
                engine.negotiate(
                    ClientHello(
                        contractVersion = TouvayContract.CONTRACT_VERSION,
                        minContractVersion = TouvayContract.MIN_SUPPORTED_CONTRACT_VERSION,
                        sdkVersionName = SDK_VERSION_NAME,
                    ),
                )
            }
        } catch (e: RemoteException) {
            runCatching { appContext.unbindService(awaiter) }
            throw TouvayException.EngineDisconnected(e)
        }

        val incompatibility = checkCompatibility(hello)
        if (incompatibility != null) {
            runCatching { appContext.unbindService(awaiter) }
            throw incompatibility
        }

        return TouvayClientImpl(engine) {
            runCatching { appContext.unbindService(awaiter) }
        }
    }

    /** Each side accepts the other iff the version windows overlap (ADR-009). */
    internal fun checkCompatibility(hello: EngineHello): TouvayException.EngineIncompatible? =
        when {
            hello.minContractVersion > TouvayContract.CONTRACT_VERSION ->
                TouvayException.EngineIncompatible(
                    "engine requires contract >= ${hello.minContractVersion}; " +
                        "this SDK speaks ${TouvayContract.CONTRACT_VERSION} — update the SDK",
                )
            hello.contractVersion < TouvayContract.MIN_SUPPORTED_CONTRACT_VERSION ->
                TouvayException.EngineIncompatible(
                    "engine speaks contract ${hello.contractVersion}; this SDK requires >= " +
                        "${TouvayContract.MIN_SUPPORTED_CONTRACT_VERSION} — update the engine",
                )
            else -> null
        }

    private class BindAwaiter : ServiceConnection {
        val engine = CompletableDeferred<ITouvayEngine>()

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                engine.completeExceptionally(
                    TouvayException.EngineUnavailable("engine returned a null binder"),
                )
            } else {
                engine.complete(ITouvayEngine.Stub.asInterface(service))
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Post-connection deaths are handled by the client's DeathRecipient.
        }

        override fun onNullBinding(name: ComponentName?) {
            engine.completeExceptionally(
                TouvayException.EngineUnavailable("engine service refused the binding"),
            )
        }
    }
}
