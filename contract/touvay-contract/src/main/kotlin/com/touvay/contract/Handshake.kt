package com.touvay.contract

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * First message a client sends on a fresh connection ([ITouvayEngine.negotiate]).
 *
 * Both sides advertise the version they implement and the oldest they accept; each side
 * decides compatibility for itself. The engine never rejects a hello — it answers with
 * [EngineHello] and lets the client decide, so old clients get a deterministic error
 * instead of a binder exception.
 */
@Parcelize
public data class ClientHello(
    public val contractVersion: Int,
    public val minContractVersion: Int,
    /** SDK artifact version, for diagnostics only. Never used for feature gating. */
    public val sdkVersionName: String,
) : Parcelable

/** Engine's reply to [ClientHello]; see that type for the negotiation rules. */
@Parcelize
public data class EngineHello(
    public val contractVersion: Int,
    public val minContractVersion: Int,
    /** Engine build version, for diagnostics only. Never used for feature gating. */
    public val engineVersionName: String,
) : Parcelable

/**
 * One capability as reported by [ITouvayEngine.listCapabilities].
 *
 * Clients feature-detect on this — never on versions (ARCHITECTURE.md §9).
 *
 * @property id namespaced capability id, e.g. `"text.rewrite"`, `"dev.echo"`.
 * @property schemaVersion version of the payload schema the engine speaks for this id.
 * @property statusCode one of [CapabilityStatusCodes].
 */
@Parcelize
public data class CapabilityInfo(
    public val id: String,
    public val schemaVersion: Int,
    public val statusCode: Int,
) : Parcelable
