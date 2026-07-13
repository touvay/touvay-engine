package com.touvay.contract

import android.os.Parcel
import android.os.Parcelable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trips every contract parcelable through a real [Parcel]. These types cross the
 * process boundary; a field silently dropped in writeToParcel is a wire-format bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParcelRoundTripTest {

    @Test
    fun streamCreditWindow_roundTrips() {
        val original = StreamCreditWindow(4, 4096, 8, 8192)

        val out = roundTrip(original)

        assertEquals(4, out.initialDeltaCredits)
        assertEquals(4096, out.initialByteCredits)
        assertEquals(8, out.maxDeltaCredits)
        assertEquals(8192, out.maxByteCredits)
    }

    @Test
    fun clientHello_roundTrips() {
        val out = roundTrip(ClientHello(contractVersion = 1, minContractVersion = 1, sdkVersionName = "0.1.0"))
        assertEquals(1, out.contractVersion)
        assertEquals(1, out.minContractVersion)
        assertEquals("0.1.0", out.sdkVersionName)
    }

    @Test
    fun engineHello_roundTrips() {
        val out = roundTrip(EngineHello(contractVersion = 1, minContractVersion = 1, engineVersionName = "0.1.0"))
        assertEquals(EngineHello(1, 1, "0.1.0"), out)
    }

    @Test
    fun capabilityInfo_roundTrips() {
        val out = roundTrip(CapabilityInfo(id = "dev.echo", schemaVersion = 1, statusCode = CapabilityStatusCodes.READY))
        assertEquals(CapabilityInfo("dev.echo", 1, CapabilityStatusCodes.READY), out)
    }

    @Test
    fun requestEnvelope_roundTrips_withCoalesceKey() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val out = roundTrip(
            RequestEnvelope(
                requestId = "req-1",
                capabilityId = "dev.echo",
                schemaVersion = 1,
                payload = payload,
                priority = RequestPriorities.INTERACTIVE,
                coalesceKey = "field-42",
            ),
        )
        assertEquals("req-1", out.requestId)
        assertEquals("dev.echo", out.capabilityId)
        assertEquals(1, out.schemaVersion)
        assertContentEquals(payload, out.payload)
        assertEquals(RequestPriorities.INTERACTIVE, out.priority)
        assertEquals("field-42", out.coalesceKey)
    }

    @Test
    fun requestEnvelope_roundTrips_withNullCoalesceKey() {
        val out = roundTrip(
            RequestEnvelope("req-2", "dev.echo", 1, ByteArray(0), RequestPriorities.BACKGROUND, null),
        )
        assertNull(out.coalesceKey)
        assertEquals(0, out.payload.size)
    }

    @Test
    fun responseDelta_roundTrips() {
        val out = roundTrip(ResponseDelta(requestId = "req-1", sequence = 7, payload = byteArrayOf(9)))
        assertEquals("req-1", out.requestId)
        assertEquals(7, out.sequence)
        assertContentEquals(byteArrayOf(9), out.payload)
    }

    @Test
    fun responseFinal_roundTrips() {
        val stats = RequestStats(ttftMillis = 12, totalMillis = 80, deltaCount = 4)
        val out = roundTrip(ResponseFinal(requestId = "req-1", payload = byteArrayOf(5, 6), stats = stats))
        assertEquals("req-1", out.requestId)
        assertContentEquals(byteArrayOf(5, 6), out.payload)
        assertEquals(stats, out.stats)
    }

    @Test
    fun engineError_roundTrips() {
        val out = roundTrip(
            EngineError(requestId = "req-1", code = EngineErrorCodes.SUPERSEDED, retryable = false, message = "superseded"),
        )
        assertEquals(EngineError("req-1", EngineErrorCodes.SUPERSEDED, false, "superseded"), out)
    }

    private inline fun <reified T : Parcelable> roundTrip(value: T): T {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION") // two-arg readParcelable needs API 33; minSdk is 29
            parcel.readParcelable<T>(T::class.java.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }
}
