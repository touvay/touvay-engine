package com.touvay.capability.tck

import org.junit.Before
import org.junit.Test

/** JUnit4 surface inherited by every production capability conformance test. */
public abstract class AbstractCapabilityTck {
    protected abstract fun subject(): CapabilityTckSubject

    private lateinit var checks: CapabilityTckChecks

    @Before
    public fun setUpCapabilityTck() {
        checks = CapabilityTckChecks(subject())
    }

    @Test public fun id01ExactKeyRegistration(): Unit = checks.id01ExactKeyRegistration()
    @Test public fun id02DuplicateRejected(): Unit = checks.id02DuplicateRejected()
    @Test public fun id03DescriptorImmutable(): Unit = checks.id03DescriptorImmutable()
    @Test public fun ds01DiscoveryStable(): Unit = checks.ds01DiscoveryStable()
    @Test public fun pl01PlanValid(): Unit = checks.pl01PlanValid()
    @Test public fun pl02PreparationDeterministic(): Unit = checks.pl02PreparationDeterministic()
    @Test public fun pl03ForwardReferenceRejected(): Unit = checks.pl03ForwardReferenceRejected()
    @Test public fun pl04PayloadBounded(): Unit = checks.pl04PayloadBounded()
    @Test public fun pl05AttemptPolicyBounded(): Unit = checks.pl05AttemptPolicyBounded()
    @Test public fun at01FreshAttempts(): Unit = checks.at01FreshAttempts()
    @Test public fun at02CloseIdempotent(): Unit = checks.at02CloseIdempotent()
    @Test public fun pr01GoldenPromptRender(): Unit = checks.pr01GoldenPromptRender()
    @Test public fun pr02DigestMismatchRejected(): Unit = checks.pr02DigestMismatchRejected()
    @Test public fun pr03RecipeMismatchRejected(): Unit = checks.pr03RecipeMismatchRejected()
    @Test public fun pr04MalformedCorpusClean(): Unit = checks.pr04MalformedCorpusClean()
    @Test public fun pr05SlotBoundEnforced(): Unit = checks.pr05SlotBoundEnforced()
    @Test public fun st01OutputDeterministic(): Unit = checks.st01OutputDeterministic()
    @Test public fun cx01AttemptCancellation(): Unit = checks.cx01AttemptCancellation()
    @Test public fun er01MalformedPayloadTyped(): Unit = checks.er01MalformedPayloadTyped()
    @Test public fun er02NoContentInErrors(): Unit = checks.er02NoContentInErrors()
}
