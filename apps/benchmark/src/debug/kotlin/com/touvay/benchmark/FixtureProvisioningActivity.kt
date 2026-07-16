package com.touvay.benchmark

import android.app.Activity
import android.os.Bundle

/** Creates the debug benchmark app's external-files directory under its own UID. */
class FixtureProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkNotNull(getExternalFilesDir(null))
        finish()
    }
}
