package com.touvay.capability.rewrite

import com.touvay.capability.tck.AbstractCapabilityTck
import com.touvay.capability.tck.CapabilityTckSubject

class RewriteCapabilityTckTest : AbstractCapabilityTck() {
    override fun subject(): CapabilityTckSubject = RewriteTestFixtures.subject()
}
