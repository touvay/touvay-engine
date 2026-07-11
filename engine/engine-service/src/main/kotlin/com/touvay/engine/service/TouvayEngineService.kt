package com.touvay.engine.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Bound service hosting the Touvay Engine in its own process (`:touvay`).
 *
 * Deliberately a *bound* service with no started/foreground mode: it lives exactly as
 * long as clients are bound, the OS reclaims it freely, and the engine survives that by
 * holding no durable state (ARCHITECTURE.md ADR-012, risk R5).
 */
public class TouvayEngineService : Service() {

    private var component: EngineComponent? = null
    private var binder: EngineBinder? = null

    override fun onCreate() {
        super.onCreate()
        val created = EngineComponent()
        component = created
        binder = EngineBinder(created)
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onDestroy() {
        component?.shutdown()
        component = null
        binder = null
        super.onDestroy()
    }
}
