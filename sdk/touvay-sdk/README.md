# Touvay Android SDK

The SDK is capability-driven. Applications see typed requests, streaming events, and
structured results; model packs, prompts, runtimes, routing, and scheduling remain
engine-private.

The SDK is the only API external client product source may use. In the embedded phase,
the host APK separately packages `engine-service` as a runtime-only artifact so the
service runs in `:touvay`; this does not expose Engine implementation types to client
source. See the
[external consumer integration guide](../../docs/integration/SDK_CONSUMER_INTEGRATION.md)
for sibling-repository Gradle setup and lifecycle requirements.

## Rewrite quick start

The invocation below is fewer than 20 lines of Kotlin:

```kotlin
val client = Touvay.connect(context)
try {
    client.rewrite().stream(
        RewriteRequest(
            text = "Can you make this clearer?",
            tone = RewriteTone.FORMAL,
            length = RewriteLength.SHORTER,
        ),
    ).collect { event ->
        when (event) {
            is RewriteEvent.Delta -> preview.append(event.text)
            is RewriteEvent.Completed -> showResult(event.result)
        }
    }
} finally {
    client.close()
}
```

Cancellation is ordinary coroutine cancellation. The SDK propagates it to the engine;
no Runtime, model, or execution object is exposed to the caller. Check
`client.capabilities()[RewriteCapability.id]` before presenting Rewrite as available.

## Connection lifecycle

- `Touvay.connect(context)` is suspend and main-safe.
- `TouvayClient` is `AutoCloseable`; scope it to the eligible visible-client interval.
- Engine death fails in-flight work with `TouvayException.EngineDisconnected`.
- The client owner reconnects explicitly by calling `Touvay.connect` again with bounded
  backoff. The SDK never auto-resubmits generative work.
- Cancelling a Rewrite collector propagates cancellation through Binder to execution.

## Compatibility

Use capability discovery, never SDK or Engine version checks. Public binary compatibility
is enforced by `apiCheck`; contract types remain private implementation details of the
SDK artifact. The current external Alpha workflow consumes the sibling Engine repository
through Gradle composite substitution; no Maven release coordinate is defined yet.
