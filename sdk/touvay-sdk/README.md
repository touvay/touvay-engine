# Touvay Android SDK

The SDK is capability-driven. Applications see typed requests, streaming events, and
structured results; model packs, prompts, runtimes, routing, and scheduling remain
engine-private.

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
