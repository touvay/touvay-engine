# Touvay SDK Consumer Integration

**Status:** Engine-side external-client handoff

**Audience:** Touvay Keyboard and other Android client repositories

**Governing contracts:** Stable Platform Architecture v1.0 and Stable Keyboard
Architecture v1.0. Any semantic change to those contracts requires an accepted ADR
before implementation.

## 1. Repository boundary

Touvay Keyboard is a separate sibling repository. Keyboard product code must not be
added to Touvay Engine.

Client production source imports only `com.touvay.sdk.*`. It must not import contract,
Engine, Capability Framework, Model Manager, Execution Engine, or Runtime types. During
the embedded-engine phase, the host application also packages `engine-service` as a
runtime-only artifact so its manifest contributes `TouvayEngineService` in the
application-local `:touvay` process. That packaging dependency is not a client API.

The resulting boundary is:

```text
Touvay Keyboard source -> touvay-sdk -> Binder/AIDL
Touvay Keyboard APK    -> engine-service runtime artifact -> :touvay process
```

## 2. Current local consumption

No Maven repository or release coordinate is defined yet. For sibling-repository Alpha
work, use Gradle composite substitution so dependency metadata and the complete
transitive Engine graph remain intact. Do not copy the SDK AAR alone: its private
contract dependency and the embedded host's transitive modules would be missing.

In the keyboard repository's `settings.gradle.kts`, adjust the relative Engine path as
needed:

```kotlin
includeBuild("../touvay-engine") {
    dependencySubstitution {
        substitute(module("com.touvay.local:touvay-sdk"))
            .using(project(":sdk:touvay-sdk"))
        substitute(module("com.touvay.local:touvay-engine-service"))
            .using(project(":engine:engine-service"))
    }
}
```

In the keyboard application module:

```kotlin
dependencies {
    implementation("com.touvay.local:touvay-sdk:local")

    // Packaging only: contributes the bound service and :touvay runtime graph.
    // Keyboard source cannot compile against Engine implementation classes.
    runtimeOnly("com.touvay.local:touvay-engine-service:local")
}
```

The consuming Android build must enable AndroidX (`android.useAndroidX=true`), as required
by the Engine's audited cryptography dependency graph.

Every APK-producing host must use the Engine's pinned NDK version so packaged native
libraries are stripped correctly:

```kotlin
android {
    ndkVersion = "27.2.12479018"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
```

After manifest merging, verify the APK contains exactly one non-exported embedded Engine
service with `android:process=":touvay"` and the `com.touvay.engine.action.BIND` intent.
The IME remains in the application process. No network permission is required by the SDK
or Engine inference path.

## 3. Embedded host pack provisioning

An embedded production host opts into one signed offline pack through application
manifest metadata owned by the host packaging layer:

```text
com.touvay.engine.host.ENABLED
com.touvay.engine.host.PACK_ID
com.touvay.engine.host.KEY_ID
com.touvay.engine.host.PUBLIC_KEY_BASE64
com.touvay.engine.host.SOURCE_DIRECTORY
```

The key ID must be the lowercase SHA-256 of the exact 32-byte Ed25519 public key.
Identifiers and source names are bounded and validated before Engine composition. The
public verification key may be packaged; private signing material must never enter the
host repository, APK, command line, or device.

Production sources are resolved only under the host application's private no-backup
directory:

```text
<host noBackupFilesDir>/touvay-pack-sources/<SOURCE_DIRECTORY>/
```

The durable canonical Model Store remains:

```text
<host noBackupFilesDir>/touvay-models/
```

The source is not a Model Store. On Engine initialization, the existing Model Manager
independently verifies the signature, exact manifest, file hashes, compatibility, and
Runtime binding before transactionally copying and activating the revision. Invalid or
incomplete metadata fails closed with Rewrite unavailable. If production host metadata
is enabled but invalid, the Engine does not fall back to demo configuration.

The legacy `com.touvay.engine.demo.*` configuration remains isolated to the Developer
Console and its app-specific demo source. It is not a production trust fallback. Each
embedded Android application has its own Engine process and private store; installing a
pack in the Console never provisions another host.

## 4. Connection ownership

`Touvay.connect(context)` is suspend, main-safe, and binds within the caller's package in
the embedded phase. It negotiates the public contract before returning. The returned
`TouvayClient` is scoped to the consumer's eligible visible-input interval and must be
closed on view teardown, sensitive-editor transition, or service destruction.

The SDK does not connect speculatively on its own. For a keyboard:

1. Render the input view and complete its first frame.
2. Confirm the current editor is eligible and device policy permits preparation.
3. Launch `Touvay.connect` from a structured AI scope outside the keystroke path.
4. Discover `text.rewrite` availability.
5. Keep ordinary typing independent of connection state.

Engine death fails in-flight work with `TouvayException.EngineDisconnected`. The client
connection owner may call `Touvay.connect` again with bounded backoff while an eligible
input view remains visible. Neither SDK nor keyboard may automatically resubmit a
generative request.

## 5. Capability discovery

Feature-detect through the SDK; never inspect SDK versions, Engine versions, model names,
pack identities, or Runtime identities:

```kotlin
val status = client.capabilities()[RewriteCapability.id]
val rewriteAvailable = status == CapabilityStatus.Ready
```

Treat every other status as unavailable. `DownloadRequired` is informational only in
this milestone: the keyboard must not download, install, or silently acquire a pack.
Rewrite becomes Ready only when the embedded Engine has already verified and activated a
compatible signed pack.

## 6. Rewrite and streaming

All Rewrite requests use the existing typed facade:

```kotlin
client.rewrite().stream(
    RewriteRequest(
        text = selectedText,
        tone = RewriteTone.NEUTRAL,
        length = RewriteLength.PRESERVE,
        outputLocaleBcp47 = null,
    ),
).collect { event ->
    when (event) {
        is RewriteEvent.Delta -> renderProvisionalDelta(event.sequence, event.text)
        is RewriteEvent.Completed -> renderFinalResult(event.result)
    }
}
```

`Delta` values are ordered, append-only, provisional preview content. They must never be
committed to the editor. `Completed.result` is authoritative and contains content-free
timing facts. The consumer must still require explicit Apply and revalidate editor epoch,
selection coordinates, and selected content before one `InputConnection` batch commit.

The SDK owns bounded credit-based streaming. Consumers must collect directly in one
structured action coroutine and must not add an unbounded callback queue or channel.

## 7. Cancellation and single-action ownership

Cancel the collection coroutine to cancel Rewrite. Cancellation propagates through the
SDK, Binder, Scheduler, execution attempt, and Runtime adapter. Cancellation is required
for user Cancel, a newer action, editor/view change, sensitive-mode transition, resource
emergency, or IME destruction.

Exactly one foreground Rewrite action may own the AI surface. Partial output is discarded
on cancellation or failure and cannot be applied. Retry is always a new explicit user
action.

## 8. Error handling

Handle SDK failures by type and display content-free product messages:

| SDK result | Consumer behavior |
|---|---|
| `EngineUnavailable` | Keep typing available; permit a later explicit reconnect |
| `EngineIncompatible` | Disable AI until Engine/SDK packaging is corrected |
| `EngineDisconnected` | Fail current action; close client; no automatic resubmission |
| `CapabilityUnavailable` | Report that offline Rewrite is not ready |
| `InvalidRequest` | Reject the selection locally; do not retry unchanged input |
| `RequestCancelled` / coroutine cancellation | Clear provisional output |
| `RequestSuperseded` | Let only the newest explicit action own the UI |
| `EngineFailure` | Respect `retryable`; retry remains explicit |
| `ClientClosed` | Treat as lifecycle completion, not an Engine retry |

Do not display or log exception messages alongside editor content. Logs, traces,
analytics, screenshots, saved state, and persistence must never contain selections,
Rewrite deltas, final text, prompts, or transcripts.

## 9. Editor and typing isolation

The SDK deliberately does not own Android editor policy. The keyboard repository must
implement the Stable Keyboard Architecture controls:

- no SDK, Binder, file, model, discovery, or AI-state call in the keystroke path;
- no bind before the first input-view frame;
- fail-closed exclusion of password, visible-password, web-password, number-password,
  no-suggestions, no-personalized-learning, and incognito editors;
- selected text read only after the explicit Rewrite action;
- epoch, coordinate, and selected-content revalidation before Apply;
- no ambient clipboard, surrounding-text, screen, retrieval, or memory access; and
- immediate action cancellation and client release on editor/view teardown.

## 10. Pack and trust setup

The SDK never exposes Model Manager. For local cross-repository engineering validation,
the Keyboard host uses its private production-host configuration and provisioning
tooling. The Developer Console may use the existing fixture in `docs/demo/demo-pack.md`;
its demo trust and Model Store are application-local and must never be copied or reused
by the Keyboard. Every host path must pass the normal signature, digest, compatibility,
catalog, and Runtime resolution pipeline.

Internal Alpha provisioning is offline and host-driven. General production catalog
delivery and representative 4 GB arm64 calibration remain separate release gates. No
downloader or network fallback is authorized.

## 11. Consumer validation gates

Before Keyboard Alpha Phase 1 is accepted in the keyboard repository, verify:

- IME launch and rapid lifecycle transitions;
- first-frame traces contain no Engine bind or discovery;
- keystroke traces contain no SDK/Binder calls;
- capability discovery and real signed-pack Rewrite end to end;
- ordered streaming preview and authoritative final replacement;
- Apply after successful editor revalidation, plus stale and commit-failure paths;
- cancellation during connect, queue, prefill, and streaming;
- engine absent, incompatible, busy, dead, and restarted scenarios;
- sensitive/incognito editors perform no bind, selection read, or request;
- no network activity or content-bearing logs/persistence; and
- the Engine repository remains green with `build`, `apiCheck`, dependency rules,
  Runtime TCK, Capability TCK, and Rewrite tests.

For source-composite integration, consumer CI should pin an exact Engine commit or tag.
Defining signed Maven publication coordinates is a separate distribution decision; it
must preserve the same API, transitive dependency, provenance, and reproducibility gates.
