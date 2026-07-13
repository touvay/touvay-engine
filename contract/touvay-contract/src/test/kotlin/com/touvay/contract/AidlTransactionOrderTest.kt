package com.touvay.contract

import android.os.IBinder
import org.junit.Test
import kotlin.test.assertEquals

/** Freezes Binder transaction ids; AIDL method reordering is a wire-breaking change. */
class AidlTransactionOrderTest {
    @Test
    fun v1TransactionsRemainStable_andV2MethodsAreAppended() {
        val first = IBinder.FIRST_CALL_TRANSACTION
        assertEquals(first + 0, transaction("negotiate"))
        assertEquals(first + 1, transaction("listCapabilities"))
        assertEquals(first + 2, transaction("submit"))
        assertEquals(first + 3, transaction("cancel"))
        assertEquals(first + 4, transaction("listTransportFeatures"))
        assertEquals(first + 5, transaction("submitWithCredits"))
        assertEquals(first + 6, transaction("grantCredits"))
    }

    private fun transaction(method: String): Int {
        val field = ITouvayEngine.Stub::class.java.getDeclaredField("TRANSACTION_$method")
        field.isAccessible = true
        return field.getInt(null)
    }
}
