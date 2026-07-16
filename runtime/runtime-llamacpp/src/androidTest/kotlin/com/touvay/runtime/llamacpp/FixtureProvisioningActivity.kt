package com.touvay.runtime.llamacpp

import android.app.Activity
import android.os.Bundle

/** Creates the test APK's external-files directory under its own UID before adb push. */
class FixtureProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkNotNull(getExternalFilesDir(null))
        finish()
    }
}
