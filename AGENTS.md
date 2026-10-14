# AGENTS.md — ToolNeuron

Operating guide for coding agents working in this repository. Everything here is verified
against the working tree. Where a claim came from `CLAUDE.md` / `README.md` but the code says
otherwise, **the code wins** and the discrepancy is called out in
[Doc accuracy](#13-doc-accuracy--what-is-stale).

`CLAUDE.md` is the deep project memory (architecture rationale, threat model, and a very long
"Things NOT to regress" list). Read it before touching security, the RAG pipeline, the remote
server, or the plugin runtime. This file is the fast path: build commands, stack, invariants,
and the rules you will get dinged for breaking.

---

## 1. What this project is

Privacy-first, offline-capable on-device AI assistant for Android. **No Google Play services,
no telemetry, no analytics, no cloud dependency.** Models, chats, RAG documents, and key
material stay on the device.

In scope:

- On-device LLM chat against GGUF models (streaming, multi-turn, thinking mode, metrics)
- Vision-language models via colocated `mmproj` projector files
- RAG over user documents (dense + BM25 hybrid, RRF fusion, citations, deep indexing)
- Voice: sherpa-onnx TTS (VITS/Piper) + STT (Whisper)
- Web search (LLM-planned DDG snippet synthesis)
- Image generation: txt2img / img2img / inpaint / 4x upscale via the `:ai_sd` AAR
- Embedded OpenAI-compatible HTTP server in its own process
- HuggingFace model browser
- First-party DEX plugin runtime with capability gating
- Dynamic-Island style overlay (`TYPE_APPLICATION_OVERLAY` pill + smart-dodge accessibility service)

Explicitly out of scope: tool calling, Termux integration, anything cloud.

---

## 2. Technologies

| Layer | Technology | Version |
|---|---|---|
| Language | Kotlin | 2.3.21 |
| Language | C++17 (via NDK) | NDK 28.2.13676358, CMake 3.31.6 (pinned in all 5 native modules) |
| Build | Gradle | 9.4.1 (wrapper, SHA-256 pinned) |
| Build | Android Gradle Plugin | 9.2.1 |
| Annotation proc | KSP | 2.3.6 |
| DI | Hilt / Dagger | 2.59.2 |
| UI | Jetpack Compose | BOM 2026.05.00 |
| UI | Material 3 Expressive | 1.5.0-alpha19 |
| UI shapes | kyant `capsule` (continuity-based squircles) | 2.1.3 |
| Navigation | Navigation Compose | 2.9.8 |
| Async | kotlinx-coroutines | 1.11.0 |
| Serialization | kotlinx-serialization-json | 1.11.0 |
| ML runtime | onnxruntime-android | 1.24.3 |
| Cross-process | AIDL | — |
| Test | JUnit 4 + androidx.test + Espresso | 4.13.2 / 1.3.0 / 3.7.0 |
| JDK | Daemon + Kotlin language level | 21 (foojay-provisioned) |
| Bytecode | `sourceCompatibility` / `targetCompatibility` | 17 |

### Platform targets

- `:app` — `compileSdk 37`, `minSdk 31`, `targetSdk 37`
- Library modules — `compileSdk 36.1`, `minSdk 29` (plugins: `minSdk 31`)
- ABIs: `arm64-v8a`, `x86_64` (every module)
- `applicationId` / namespace: `com.dark.tool_neuron`
- `versionCode 31`, `versionName 3.0` (in `gradle.properties` as `tn.versionCode` / `tn.versionName`)
- `jniLibs.useLegacyPackaging = true` everywhere

### Prebuilt AARs (`libs/`, never edited, refreshed by binary replacement)

| AAR | Provides | Notes |
|---|---|---|
| `tn_security-release.aar` | `com.dark.tn_security` — `TnSecurity`, `TnModule`, `LogcatSink`, `TnCode`/`TnStage` | Structured logging + crash capture. Wired in `TNApplication`, `InferenceService`, `CrashReportActivity`. |
| `gguf_lib-release.aar` | `com.dark.gguf_lib` — `GGMLEngine`, `EmbeddingEngine` | llama.cpp / GGUF. Also bundles the chat/VLM/embedding engine. |
| `ai_sherpa-release.aar` | `com.dark.ai_sherpa` — `OfflineTts`, `OfflineRecognizer` | sherpa-onnx. Also bundles `libonnxruntime.so` — this is why the app-level ORT pin must match it. |
| `ai_sd-release.aar` | `com.dark.ai_sd` — `StableDiffusionManager` | Stable Diffusion via QNN (Snapdragon NPU) / MNN (CPU). **Back in the tree** after commit `7764e295`. |

Because these AARs are consumed as `implementation(files(...))`, Gradle does **not** pull
transitive POM dependencies. Anything the AARs need at runtime (e.g. `commons-compress`, `xz`
for `:ai_sd`'s `qnnlibs.tar.xz` extraction) must be declared explicitly in `app/build.gradle.kts`.

### Native dependencies (fetched at build time via CMake `FetchContent`)

- `:hxs_encryptor` → BoringSSL + liboqs
- `:native-server` → cpp-httplib v0.18.5 + nlohmann/json v3.11.3 (header-only, **no BoringSSL / OpenSSL / zlib**)
- `:networking` → prebuilt curl-impersonate in `src/main/cpp/prebuilts`, bundled `cacert.pem`
- `:download_manager` → own C++ downloader with a JNI bridge

---

## 3. Repository layout

```
ToolNeuron/
├── app/                 # UI, viewmodels, Hilt graph, activities, services, AIDL
├── hxs/                 # Encrypted key-value store — Kotlin HexStorage + C++ core
├── hxs_encryptor/       # Security primitives + native policy/auth/boot-integrity stack
├── native-server/       # Embedded OpenAI-compatible HTTP server (cpp-httplib C++)
├── networking/          # HTTP primitives (curl-impersonate) via WebNative
├── download_manager/    # Native downloader with JNI bridge
├── plugin-api/          # Pure-Kotlin contract plugins compile against (compileOnly deps)
├── plugin-exc/          # Plugin host runtime: DexClassLoader, capability gate, dock
├── plugins/
│   ├── notes/           # First-party plugin — standalone Android application module
│   ├── counter/
│   └── expense/         # Ships an ONNX model (MiniLM) for AI auto-categorization
└── libs/                # Prebuilt AARs
```

Gradle modules (12): `:app`, `:hxs`, `:hxs_encryptor`, `:download_manager`, `:networking`,
`:native-server`, `:plugin-exc`, `:plugin-api`, `:plugins:notes`, `:plugins:counter`,
`:plugins:expense`.

> **Naming collision warning.** `com.dark.tool_neuron.plugin.*` (in-app *web* plugin system:
> `PluginModule`, `WebPlugin`, `PluginHubScreen`) is a **different, older system** from
> `com.dark.plugin_exc.*` / `com.dark.plugin_api.*` (DEX plugin runtime:
> `PluginExecutor`, `PluginRegistry`, `PluginInstallScreen`). Both are live in `:app`. Two
> classes are named `PluginRegistry` and two are named `Plugin` — always check the import.

### `:app` source map (`com.dark.tool_neuron`)

| Package | Contents |
|---|---|
| `TNApplication` | `@HiltAndroidApp`; boot order: `TnSecurity.init` → `WebNative.ensureReady` → start/bind `:inference` → env scan → `bootVerify` → `verifyHookBaselines` → a11y scan → `appLockObserver.register()` |
| `activity/` | `MainActivity`, `TempActivity`, `ColorShowcaseActivity` |
| `data/` | Security + prefs: `AppKeyStore`, `SessionHolder`, `SecurityManager`, `SecurityModule`, `AppPreferences`, `AuthState`, `VerifyResult`, `LockoutPolicy`, `NativeIntegrity`, `AppLockObserver`, `AccessibilityGuard`, `RootGuard`, `PinStrength`, `SocBucket`, `ThemeController` |
| `model/` | `NavScreens`, `Chat`, `ChatDocument`, `Citation`, `ModelInfo`, `HuggingFaceModel`, `WebSearchEvent`/`WebSearchUiState`, enums |
| `repo/` | Repositories: chat, documents, downloads, HF (`HuggingFaceApi`, `hf/*`), RAG (`RagManager`, `RagChunker`, `RagReranker`, `RagQueryRewriter`, `RagDocSummarizer`, `RagRaptor`, `RagKeywordIndex`, `SourceFileVault`), image gen, storage inspector, VLM vision cache, web search |
| `service/inference/` | `InferenceService` (`:inference` process), `InferenceClient`, `InferenceStopReceiver`, `CrashInfo`, `HxsTnSink` |
| `service/server/` | `RemoteServerService` (`:server` process), `ServerController`, `ServerEngineRegistry`, per-engine wrappers, `ServerInferenceBridge`, `ServerCatalog`, `ServerWavCodec`, `BindResolver` |
| `service/island/` | Dynamic-Island overlay service + accessibility dodge service (registered; opt-in via overlay permission + accessibility settings) |
| `plugin_host/` | Hilt wiring for the DEX plugin host (`PluginContainerHost`, `PluginHostModule`) |
| `viewmodel/` | All ViewModels live here (never co-located with screens). `home_vm/` holds `HomeViewModel`'s collaborators |
| `ui/` | `screens/*`, `components/*`, `theme/*`, `icons/TnIcons`, `navigation/TNavigation`, `screens/system_ui/{AppScaffold,AppTopBar,AppBottomBar}` |
| `util/` | `SecureClipboard`, `VlmPaths`, `ChatExporter`, `ChatShareHelper`, `ImageExport`, `MarkdownStripper`, `FormatUtils` |
| `voice/` | `TtsPlayer`, `SttRecorder`, `VoiceArchive`, `VoiceModelManager` |
| `aidl/` | `IInferenceService`, `IGenerationCallback`, `IModelLoadCallback`, `IDiffusionCallback`, `ITnEventCallback`, `server/IRemoteServerService`, `server/IRemoteServerCallback` |

---

## 4. Architecture invariants

These are load-bearing. Violating them breaks the app in ways that compile fine.

### Three processes

| Process | Owner | Contains |
|---|---|---|
| `:app` (main) | `MainActivity` | UI, ViewModels, Hilt graph, **the DEK and all HXS access**, `ServerController` (AIDL client) |
| `:inference` | `InferenceService` | GGUF chat/VLM engine, sherpa-onnx voice, SD runtime, downloads. Dies with the app. |
| `:server` | `RemoteServerService` | Foreground (`dataSync`, `stopWithTask="false"`), its **own** engine instances, the native HTTP server, the bearer token in native memory. Survives swipe-from-recents. |

**`:inference` and `:server` must never open HXS or unwrap the DEK.** The Keystore alias is
uid-scoped but the signer binding is what actually protects cross-build reads, and unwrapping
out-of-process is forbidden by design. Push everything across AIDL.

### Persisted storage — HXS only

No `SharedPreferences`, no Room, no DataStore, no raw files for user data. Every vault is
`HexStorage.openEncrypted` with a signer-bound HKDF user key. Current keys (all `.v2`):

| Info string | Vault dir |
|---|---|
| `tn.app_prefs.user_key.v2` | `app_prefs/` |
| `tn.app_prefs.auth_key.v2` (second AEAD layer, AAD `tn.auth_state.v1`) | `app_prefs/` |
| `tn.chats.user_key.v2` | `chat_store_v2/` |
| `tn.chat_documents.user_key.v2` | `chat_documents_meta_v1/` |
| `tn.chat_doc_source.user_key.v2@<sourceId>` (per file, AAD = sourceId) | `chat_documents/sources_v2/` |
| `tn.rag_keyword.user_key.v2` | `rag_keyword_v1/` |
| `tn.download_history.user_key.v2` | `download_history_v1/` |
| `tn.vlm_vision_cache.user_key.v2` | `vlm_vision_cache/` |
| `tn.plugins.user_key.v2` | `plugin_store_v2/` |

Intentional non-HXS exceptions (and only these):

- `app_bootstrap/k.bin` — the Keystore-wrapped DEK, XOR-masked; must live outside the vault
- `chat_documents/sources_v2/<id>.bin` — per-file AEAD via `SourceFileVault`
- `models/`, `voice/`, `sd_models/`, `sd_upscalers/`, `plugins/` — model/plugin payloads
- `config/repositories.json`, `cache/model_catalog.json` — non-sensitive config/cache
- `tn_security/{crashes,events}/` — diagnostics

Every `deriveKey` call in app code **must** pass `salt = keyStore.installSignerHash()`.
Anything else collapses the same-device replaced-APK defense.

### One Scaffold

`AppScaffold` is the only `Scaffold` in the app. Screens take `innerPadding: PaddingValues`
and render plain `Column` / `LazyColumn` / `Box`. Per-route bars are dispatched from the
`when` blocks in `ui/screens/system_ui/AppTopBar.kt` and `AppBottomBar.kt`.

### Native build flags

Every owned native CMake target: C++17, `-fvisibility=hidden`, `-fstack-protector-strong`,
LTO + gc-sections + icf on release, `-march=armv8-a+crypto+sha2` on arm64, and
`-Wl,-z,max-page-size=16384` (Android 15+ / Play requirement).

---

## 5. Useful Commands

Run via the Gradle wrapper. On Windows use `.\gradlew.bat`; on POSIX shells use `./gradlew`.

### Build

```sh
# Fast compile check — the default "did I break it" gate
./gradlew :app:compileDebugKotlin

# Install on device/emulator
./gradlew :app:installDebug

# Release (R8 + resource shrink). Falls back to unsigned if keys are absent.
./gradlew :app:assembleRelease

# Build one module in isolation
./gradlew :hxs:assembleDebug
./gradlew :plugin-exc:assembleDebug
```

> **Never `assemble` + `adb install` for the dev loop.** Use `:app:installDebug`.

### Native

```sh
# CMake configure + build a native module
./gradlew :hxs_encryptor:externalNativeBuildDebug
./gradlew :native-server:externalNativeBuildDebug
./gradlew :networking:externalNativeBuildDebug
./gradlew :download_manager:externalNativeBuildDebug
```

clangd / the LSP flags `openssl/mem.h` not found and similar as false positives —
**build-green is the source of truth, not the language server.**

CMake is pinned to an **exact patch version** (`3.31.6`) in all five native modules.
If you see `[CXX1300] CMake '3.31.6' was not found in SDK, PATH, or by cmake.dir property`:

- AGP matches the pin exactly. Having *a* newer CMake installed is not enough.
- `cmake.dir` in `local.properties` **cannot** substitute a different version — pointing it at
  a directory yields `CXX1300`, pointing it at the executable yields `CXX1301`. It only helps
  when the override has the matching version.
- Two valid fixes: `sdkmanager "cmake;3.31.6"`, or bump `version = "…"` in all five modules
  together (`:hxs`, `:hxs_encryptor`, `:native-server`, `:networking`, `:download_manager`).
  A first-time `configureCMakeDebug` takes several minutes because FetchContent pulls
  BoringSSL, liboqs, cpp-httplib, and nlohmann/json.
- `sdk.dir` belongs in `local.properties` (gitignored); it falls back to `$ANDROID_HOME` /
  `$ANDROID_SDK_ROOT` when absent.

### Tests

```sh
# Instrumented tests (needs a connected device or running emulator)
./gradlew :app:connectedDebugAndroidTest
./gradlew :hxs:connectedDebugAndroidTest
./gradlew :hxs_encryptor:connectedDebugAndroidTest
./gradlew :networking:connectedDebugAndroidTest

# JVM unit tests (only the AS ExampleUnitTest templates actually exist)
./gradlew :app:testDebugUnitTest

# Single test class
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dark.tool_neuron.PhaseOneSecurityTest
```

There is **no CI**, no lint baseline, no detekt/ktlint/spotless, and no git hooks.
Nothing runs automatically — verification is a manual step you must perform and report.

### Plugins

```sh
# Build a first-party plugin into an installable zip
./gradlew :plugins:notes:packagePlugin
./gradlew :plugins:counter:packagePlugin
./gradlew :plugins:expense:packagePlugin

# All three
./gradlew :plugins:notes:packagePlugin :plugins:counter:packagePlugin :plugins:expense:packagePlugin
```

Output: `plugins/<name>/build/plugin/<name>.zip`, containing `manifest.json`,
`classes*.dex`, `lib/<abi>/*.so`, and any extra assets (expense ships `model.onnx` + `vocab.txt`).

Publish flow: drop the zip under `plugins/<id>/<version>/` in the HF repo
`Void2377/tool-neuron-plugins`, add a `plugins.json` entry, then `hf upload Void2377/tool-neuron-plugins . .`

### Diagnostics / verification

```sh
# Confirm native detection strings are not plaintext
strings libhxs_encryptor.so | grep -iE '^(frida|gadget|linjector|xposed|TracerPid)$'   # expect: no output

# Confirm onnxruntime pin matches the sherpa AAR's bundled engine
strings libs/ai_sherpa-release.aar | grep ^1\.2

# Confirm R8 kept the JNI surface (release build only)
grep com.dark.networking.WebNative app/build/outputs/mapping/release/mapping.txt   # expect identity mapping

# Confirm 16 KB page alignment (Android 15+ / Play)
unzip -p libs/ai_sherpa-release.aar jni/arm64-v8a/libai_sherpa.so > /tmp/s.so
readelf -l /tmp/s.so | awk '/LOAD/{getline;print $NF}'    # expect: 0x4000

# Device-side debugging
adb shell dumpsys accessibility      # island overlay window is tagged "TnIsland"
adb logcat -s InferenceService:V
```

### Gradle housekeeping

```sh
./gradlew --stop
./gradlew clean
./gradlew :app:dependencies          # resolve tree when a transitive dep looks missing
```

`org.gradle.configuration-cache=true` is on. A module-local `repositories {}` block will fail
the build — `dependencyResolutionManagement` uses `FAIL_ON_PROJECT_REPOS`.

---

## 6. Hard rules

1. **HXS-only persisted storage.** No `SharedPreferences` / Room / DataStore / raw files for
   user data. Exceptions listed in §4 are exhaustive.
2. **Security decisions live in C++/JNI.** Kotlin wraps native. Every auth / trust / policy
   decision is made in `libhxs_encryptor.so` and crosses JNI as an opaque token or bool. No
   boolean-trust-through-JNI, no Kotlin-side `if (verify)` gating.
3. **Never write fully-qualified class names inline.** `import` at the top, short name in the body.
4. **ViewModels live under `com.dark.tool_neuron.viewmodel`.** Never co-locate a VM with its screen.
5. **No comments in source** except a one-liner `//` for a non-obvious *why*. No decorative
   banners, no block comments, no KDoc on internal/private. Names and structure must
   self-document. (Configuration files — `*.pro`, `*.gradle.kts` — do use explanatory comments.)
6. **Library modules must NOT minify.** Only `:app` sets `isMinifyEnabled = true`. Library
   rules go in each module's `consumer-rules.pro`.
7. **No TODOs, stubs, or half-implementations.** Every task is coded end to end.
8. **Never push without an explicit ask.** Don't commit unless asked. Conventional commits,
   no `Co-Authored-By` trailer.
9. **No spec / plan / research docs in the repo.** Project memory lives in `CLAUDE.md`.
   Implementation roadmaps belong in conversation context.
10. **Research / exploration subagents run at low effort**, not max, unless overridden.
11. **Update `CLAUDE.md` in the same change** as any security-, schema-, or
    API-surface-affecting edit. If the `CLAUDE.md` update isn't in the diff, the change isn't done.

---

## 7. Security guidelines

Trust decisions live in C++ so a Kotlin-layer bypass doesn't grant access. Do not erode this.

### Boot sequence (main process, `TNApplication.onCreate`)

1. `TnSecurity.init(TnModule.TN_APP, ...)` + `LogcatSink` + `HxsTnSink`, then `drainCrashFiles`.
   Runs in **all** processes, before everything else.
2. Non-main processes return early — they never touch integrity or prefs.
3. `WebNative.ensureReady()` installs the CA bundle + curl-impersonate profile. Must precede
   any network call; `:networking` fails closed without it.
4. `startForegroundService(:inference)` then `InferenceClient.bind(this)`.
5. `integrity.scanProcessEnvironment()` → hard-fail **only** on `FAIL_DEBUGGER | FAIL_FRIDA`.
   `FAIL_XPOSED` is recorded into `TNApplication.softEnvReasons` and surfaced later as a
   one-time warning dialog.
6. `integrity.bootVerify()` → TOFU `.so` manifest rebound to install identity. Mismatch → `hardFail`.
7. `BootIntegrity.verifyHookBaselines()` → inline-hook detection.
8. `accessibilityGuard.scan()` → no longer a hard-fail; flows to `RootWarningDialog`.
9. `appLockObserver.register()`, plugin host init.

`BootIntegrity.hardFail(reasons)` → `PolicyEngine.markTampered()` + `_exit(1)`, unless
`setRelaxedForTesting(true)`.

### What must never regress

- **Never re-introduce `Settings.Secure.ANDROID_ID`.** Keystore-attested identities only.
- **Never take Argon2id below `t=4 / m=131072 / p=1`** (constants in `hxs_encryptor/src/main/cpp/auth.h`).
- **Never route a gated feature around `PolicyEngine.isAllowed(featureId, sessionToken)`.**
  Feature IDs `>= 1000` are the reserved `PRO_*` range and currently return `false` — that
  branch is the monetization hook.
- **Never expand `is_unauth_feature` in `policy_engine.cpp`** without an explicit threat-model review.
- **Never emit plaintext detection strings in native code.** Wrap with `HXS_OBF`.
- **Never add a zero-fallback to signer-hash computation.** If the platform can't read the
  install signer, throw `SecurityException` and refuse to bootstrap.
- **Never drop the signer-binding salt** from any `deriveKey` call.
- **Never bump a user-key info string without bumping its version suffix** — it invalidates
  existing vaults and the open-or-rebuild helper wipes them.
- **Never add a new HTTP route without going through auth pre-routing.** Only `/`,
  `/index.html`, `/webui`, `/health` are public. Bearer compare is constant-time; 20
  consecutive auth failures from one client → 1 h ban.
- **Never accept network URLs in `image_url` parts on the server.** Data URLs only; `http(s)://`
  returns 400. Offline-only scope.
- **Never log full URLs with query strings.** Status code + host only — search queries are PII.
- **Never send device-identifying headers** to `WebNative.fetch`. The header map is for
  protocol headers (`Accept`, `Accept-Encoding`) only.
- **Never call `HttpURLConnection` for HuggingFace or web search.** `:networking`
  (`WebNative.fetch`) is the only allowed pipe.
- **Never send VLM image bytes as `byte[]` over AIDL.** 1 MB binder limit — use
  `ParcelFileDescriptor[]`.
- **Never pass big binary payloads as `byte[]` over JNI** in `:native-server`. Stage to
  `<cacheDir>/server-staging/` and hand the *path* across as a Java string.
- **Never `secureWipe` the userKey passed to `HexStorage`** — the C++ side holds a
  `NewGlobalRef`; zeroing turns every later AEAD op into a zero-key op.
- **Never zero `AppKeyStore.cachedDek` in place** — same `NewGlobalRef` aliasing.
- **Never run Argon2id on the main thread.** ~1.5 s per call. Wrap in
  `viewModelScope.launch { withContext(Dispatchers.Default) { … } }`.
- **Never eagerly construct `AppKeyStore` or `AppPreferences` outside the main process.**
  Inject as `dagger.Lazy<T>`.
- **Never inject `DownloadCoordinator` / `DownloadHistoryRepository` from `:server` or
  `:inference`.** Route through AIDL.

### Obfuscation-sensitive surfaces

`app/proguard-rules.pro` keeps are load-bearing in both directions:

- **Prebuilt AAR JNI entry points** — `com.dark.gguf_lib.**`, `com.dark.ai_sherpa.**`,
  `com.dark.ai_sd.**` must keep their original names or `dlsym` fails at runtime.
- **The plugin-visible ABI** — `com.dark.plugin_api.**`, `kotlin.**`, `kotlinx.coroutines.**`,
  `kotlinx.serialization.**`, the AndroidX + Compose surface, and `ai.onnxruntime.**` must not
  be renamed or repackaged. Plugin `.dex` files are compiled against those exact names and are
  loaded with the app as parent classloader. **Adding a library to `:plugin-api` means adding
  it to that keep block too.**
- `com.dark.networking.**` keeps — without them every HF / web-search / model-catalog call
  dies on release with `UnsatisfiedLinkError` while the build stays green.

Release hardening: `isMinifyEnabled`, `isShrinkResources`, `isDebuggable = false`,
`isJniDebuggable = false`, `Log.d/v/i` stripped via `assumenosideeffects`, `-repackageclasses ''`,
`allowBackup=false`, `largeHeap=true`.

---

## 8. Best practices

### Compose / UI

- One `Scaffold` (`AppScaffold`). Screens take `innerPadding: PaddingValues`.
- Theme tokens come from `LocalDimens` / `LocalTnShapes` / `MaterialTheme.colorScheme`. No inline
  `dp` literals for generic spacing, sizing, or colors. Identity constants (island pill width,
  swipe threshold, press scale) live in a dedicated `object` like `IslandGeometry`.
- Icons come from `TnIcons` (a stroke-based, 24x24-viewport set in `ui/icons/TnIcons.kt`).
  Do not add raw `Icons.*` material imports.
- Prefer one `animateFloat` driving a `progress` value and `lerp()`-derive everything from it,
  rather than N independent animations that can desync on different frames.
- Never `LazyListState.scrollToItem` on every streaming token. Track `stickToBottom` from
  `snapshotFlow { isScrollInProgress }` and gate the auto-scroll effect on it.
- Model selection dialogs, nav routes, and top/bottom bars dispatch from
  `AppTopBar.kt` / `AppBottomBar.kt` / `TNavigation.kt`.

### Data / repositories

- One `@Singleton` repository per vault, opened once, exposing a `StateFlow`. Views observe;
  they never open storage.
- `StateFlow` is the single source of truth for mutable UI state; `SharedFlow` only for events.
  Prefer `tryEmit` into a buffered `SharedFlow` inside `catch (CancellationException)` blocks.
- Cross-process state goes through AIDL, never a shared singleton.
- Watch side effects: Argon2id, PFD reads, archive extraction, bitmap decode, and file I/O
  all belong on `Dispatchers.IO` / `Dispatchers.Default`.

### RAG

Retrieval flow is a fixed pipeline. Keep the order:

```
optional multi-query rewrite (8 s)
  → per-query (dense top-20 + BM25 top-20)
  → RRF fusion (k=60, identity = (docId, chunkIndex), pool = 12)
  → optional LLM rerank (15 s, 256 tokens)
  → token-budget trim
  → FINAL_TOP_N = 8
```

- Never put rerank before fusion — the rerank LLM has nothing to rank yet.
- `RagKeywordIndex` lives in C++ (`hxs::RagKeywordIndex`, `hxs/src/main/cpp/rag_keyword.cpp`).
  Do not move it back to Kotlin or SQLite (some OEMs ship SQLite without FTS5).
- Only text-like documents hit BM25 (`RagManager.isTextLike`). Binary formats get dense-only
  retrieval until a native text-extraction API lands.
- Chat-message persistence TAGs are load-bearing: `TAG_MSG_CITATIONS = 13`,
  `TAG_MSG_WEBSEARCH_RUN = 14`, `TAG_MSG_WEBSEARCH_STATE = 15`. New message fields use TAG ≥ 16.
  Older records must keep decoding with defaults.
- Chat document id is `"$chatId:$sourceId"` — never a UUID. Re-attach must stay idempotent and
  `removeDocument` must reference-count the shared source blob.

### Remote server

- Never bypass `ServerEngineRegistry` from a route handler. Per-kind locks (a `Mutex` for
  suspend loaders, plain `synchronized` for sherpa-onnx) serialise concurrent loads.
- New endpoint checklist: a new typed `Kind` in `server_models`, a wrapper engine class, a
  registry method, an `InferenceBridge.start<X>` upcall, a `nativeFeedReply*` consumption path,
  and the route in `server_core.cpp`.
- The server must stay lazy-loaded per engine kind with a primary chat model preloaded at start.
- Server lockdown is UI-routing (`AppScaffold` re-routes to `ServerScreen` when running,
  `BackHandler` absorbs back, drawer hidden) **plus** `serverController.isBusy` gating on the
  chat-side load/unload/send paths.
- `:server` owns its own sherpa-onnx and SD instances. Do not route them through
  `InferenceClient` AIDL — that would collide with in-app voice/image state.
- `reply_session.wait(timeout_ms)` timeouts are sized per endpoint. Don't collapse them to 0.

### Plugin runtime

- Plugin `manifest.json` capabilities are the **only** declaration mechanism. If a plugin
  calls an API whose capability it didn't declare, `CapabilityGate.require` throws
  `SecurityException` — that is the contract, don't add an allow-by-default path.
- Plugins are installed to `<filesDir>/plugins/<id>/` as `manifest.json` + `classes*.dex` +
  optional `lib/<abi>/*.so` + assets. Dex and `.so` files are chmod'd read-only after
  extraction (Android 14+ rejects writable dex).
- Bundle download is SHA-256-verified against `plugins.json` **before** extraction, case-insensitive.
- `PluginBundle.sanitizeEntryName` must keep dropping entries containing `..` or starting with
  `/` / `\`. Same for the ZIP extraction path in `ModelStoreViewModel` — a malicious mirror
  could craft traversal entries.
- Per-plugin HXS namespace is `plugin_<id>` (collection, index on TAG 1 = key). Uninstall drops it.
- `NetworkApi` is GET-only today. `OnnxOptions.useNnapi` / `useXnnpack` are ignored — the host
  picks the EP from `AppPreferences.pluginOnnxEp`. Don't assume plugin-requested options win.

### Testing

Every instrumented test that touches native global state **must** bracket with:

```kotlin
@Before fun setup() {
    File(context.filesDir, "app_bootstrap").deleteRecursively()
    File(context.filesDir, "app_prefs").deleteRecursively()
    PolicyEngine.resetForTesting()
    BootIntegrity.setRelaxedForTesting(true)   // omit only if the test asserts the real tamper latch
}
@After fun cleanup() {
    BootIntegrity.setRelaxedForTesting(false)
    PolicyEngine.resetForTesting()
}
```

- Use `@FixMethodOrder(MethodSorters.NAME_ASCENDING)` — several suites are order-dependent
  (backoff sequences, vault rebuilds).
- `context` is `InstrumentationRegistry.getInstrumentation().targetContext`.
- Call `HexStorage().close()` when reopening a vault; the C++ globals (`g_manifest`,
  `g_collections`, `g_crypto`) are process-wide.
- `:networking`'s `WebNativeSmokeTest` requires live network and will fail offline — say so
  rather than treating it as a regression.

### Code style

- `kotlin.code.style=official`.
- `import` at the top, never inline FQCNs.
- Conventional commits, no `Co-Authored-By`, never push unprompted.
- One-liner `//` comments only, for *why*. Names self-document.
- Keep `app/build.gradle.kts` dependency declarations explicit for anything a file-based AAR
  needs at runtime (no transitive POM resolution).

---

## 9. Common recipes

### Add a screen

1. `model/NavScreens.kt` — add a `NavScreens.X("route")` entry.
2. `ui/screens/<name>/XScreen.kt` — signature `XScreen(innerPadding: PaddingValues)`.
3. `ui/screens/<name>/XTopBar.kt` if it needs a bar.
4. `ui/navigation/TNavigation.kt` — register the `composable(...)` route.
5. `ui/screens/system_ui/AppTopBar.kt` — add the `when` case for the top bar.
6. `ui/screens/system_ui/AppBottomBar.kt` — if it needs a bottom bar.
7. `viewmodel/XViewModel.kt` — `@HiltViewModel class XViewModel @Inject constructor(...)`.
8. Nothing in step 2 may create a `Scaffold`.

### Add a guide entry

`ui/screens/guide/AppGuideScreen.kt` (category list) → `GuideEntryKeys` → `NavScreens` →
detail screen using `GuideDetailLayout` → `TNavigation` `composable(...)` → `AppTopBar.kt` `when`.

### Add a chat-message field

New TAG ≥ 16 in `ChatRepository`. Add the field to `ChatMessage` with a default so old records
decode. Update both the write path and `buildMessagesJson` if the LLM should see it.

### Add a native module

New `:module` with an `android-library` plugin, `namespace`, `compileSdk 36.1`, `minSdk 29`,
`abiFilters`, `useLegacyPackaging = true`, `isMinifyEnabled = false`, `consumer-rules.pro`,
and a `src/main/cpp/CMakeLists.txt` with the shared flags from §4. Register in
`settings.gradle.kts` and add the R8 keep block in `app/proguard-rules.pro`.

### Add a plugin

1. `plugins/<name>/` — new `android.application` module, `compileOnly(project(":plugin-api"))`
   plus `compileOnly` Compose / coroutines / serialization. No Hilt, no KSP.
2. `class XPlugin : Plugin` with a no-arg constructor; own `MaterialTheme` and scaffold.
3. `manifest.json` at the module root with `id`, `version`, `apiVersion`, `name`,
   `description`, `author`, `entryClass` (FQCN), `initial` (one glyph), `capabilities`,
   `hasNativeCode`.
4. A `packagePlugin` Zip task: `include("classes.dex", "classes*.dex", "lib/**/*.so")` plus
   `manifest.json` and any assets.
5. Add `plugins.json` entry in the HF repo and upload.

---

## 10. Known traps

| Trap | Detail |
|---|---|
| R8 breaks plugin ABI silently | Build is green, plugins crash with `NoClassDefFoundError` at install time. Verify the keep block in `app/proguard-rules.pro` covers every library `:plugin-api` exposes. |
| `UnsatisfiedLinkError` after R8 | A `-keep` for a JNI-exposed class was dropped. Symptom is a runtime crash, not a build failure. |
| clangd false positives on native | `openssl/mem.h` not found etc. Ignore; trust build-green. |
| Two `PluginRegistry` / `Plugin` types | `:app`'s legacy web-plugin system vs. `:plugin-exc`'s DEX runtime. Check imports. |
| 16 KB page alignment | Android 15+ / Play Store require `0x4000` LOAD alignment on arm64 + x86_64. `-Wl,-z,max-page-size=16384` in every owned native target. |
| `RemoteCallbackList` broadcast races | `beginBroadcast()` is not nestable. Guard fan-out with `sdBroadcastLock` or `:inference` crash-loops. |
| `tryEmit` vs `emit` in catch blocks | A `catch (CancellationException)` on a cancelled scope with `withContext(Dispatchers.Default)` never delivers. Use `tryEmit` into a buffered flow. |
| AIDL rebind hangs | `_service.first { it != null }` must be wrapped in `withTimeoutOrNull(BIND_TIMEOUT_MS)`; all four `ServiceConnection` death paths must call `failPendingLoads`. |
| Big models on low-RAM devices | `InferenceService.loadModel` has a pre-load `MemAvailable` check (`modelSize * 6 / 5`). Do not remove it. |
| Binding `:inference` with `BIND_IMPORTANT` | Elevates `:inference` into `:app`'s OOM bucket, so the lowmemorykiller evicts the wrong process. Use `BIND_AUTO_CREATE` only. |
| Multi-dex plugin bundles | `PluginLoader.loadInstalled` requires a literal `classes.dex` even though `materialise` handles `classes*.dex`. A multidex-only bundle installs but silently vanishes from the registry. |
| Unused plugin capabilities | `CAMERA`, `MIC`, `FILESYSTEM_READ/WRITE`, `NOTIFICATIONS`, `CLIPBOARD` are declared in the enum but have no enforcement call site. Don't assume declaring them does anything. |
| `DiagApi` is always `null` | `PluginExecutor.open()` never populates `context.diag`. |
| `.idea/` is partially git-tracked | Includes a hardcoded physical device serial in `deploymentTargetSelector.xml`. Don't add more machine-local IDE state. |
| `.gitignore` misses `*/build/` | Only the root `/build` is ignored. Module build dirs aren't matched. |

---

## 11. Testing

| Module | Suite | Coverage |
|---|---|---|
| `:app` | 52 instrumented tests, 8 classes | `PolicyEngine` gating, `AuthNative`, `AppKeyStore` DEK lifecycle, `AuthState` round-trips, on-disk **plaintext-leak scans**, TOFU manifest + hard-fail, lockout/backoff/wipe, clock rollback, panic PIN, PIN strength, `SecureClipboard`, `.so` string obfuscation scan |
| `:hxs` | 68 instrumented tests, 2 classes | `HexStorage` CRUD, indexing (`WIRE_BYTES`/`VARINT`/`FIXED64`), flush + reopen persistence, schema versioning, `HxsRecord` encode/decode round-trips |
| `:hxs_encryptor` | 48 instrumented tests | AEAD round-trips, wrong-key / wrong-AAD / tampered-ciphertext rejection, HKDF, Argon2id, PBKDF2, cipher detection, debugger/frida/xposed probes, APK signature verify, hybrid KEM (ML-KEM-768 + X25519), hybrid signatures (ML-DSA-65 + Ed25519) |
| `:networking` | 2 instrumented tests | `WebNative.ensureReady()` + one live `WebNative.search` call (**requires internet**) |
| others | no test sources | `download_manager`, `native-server` have AS-template-only `Example*` tests. `:plugin-exc`'s dead templates were deleted (they declared no test deps and could not compile); `:plugin-api` and `:plugins:*` never had any. |

No JVM unit tests beyond the templates — everything meaningful is instrumented because almost
every module is JNI-backed and the host JVM can't load the `.so`.

---

## 12. Git hygiene

- Conventional Commits. No `Co-Authored-By` trailer. No push without an explicit ask.
- There are **no git hooks** in this repo (`core.hooksPath` unset, `.git/hooks/` all samples).
  "Never skip hooks" in `CLAUDE.md` refers to a convention that isn't enforced.
- Actual recent history drifts from Conventional Commits (`Updated: Fixed Some Bugs`).
  Match the documented convention, not the recent log.

---

## 13. Doc accuracy — what was stale

Trust the source over these. The rows marked ✅ were corrected in `CLAUDE.md`; the rest are
still open.

| Claim | Reality at HEAD | Status |
|---|---|---|
| `CLAUDE.md`: "minSdk 29 / targetSdk 36" (`:app`) | `minSdk 31`, `targetSdk 37`, `compileSdk 37` | ✅ fixed |
| `CLAUDE.md`: "`ai_sd` AAR was removed in `9d79a3b`, drop the keep rule" | The AAR is **back** (`7764e295`), in `libs/`, in `app/build.gradle.kts`, and the `com.dark.ai_sd.**` keep is live. Code (`ImageGenManager`, `ImageTaskViewModel`, `ServerImageEngine`) references `StableDiffusionManager`. | ✅ fixed |
| `CLAUDE.md`: "`CapabilityGate` (PolicyEngine.hasSession + manifest cap check)" | It checks `PolicyEngine.isTampered()` only — no session check. | ✅ fixed |
| `CLAUDE.md`: "onnxruntime-android 1.20.0" | `1.24.3`, and it must stay in sync with the copy bundled in `ai_sherpa-release.aar`. | ✅ fixed |
| `CLAUDE.md` / `README`: vault under `plugin_store` | `plugin_store_v2/`, key `tn.plugins.user_key.v2`. | ✅ fixed (CLAUDE.md) |
| `README`: `tn.app_prefs.user_key.v1` | `.v2` (signer-bound). All vaults are `.v2`. | ✅ fixed (plus Testing section with counts + no-CI note) |
| `CLAUDE.md` / `README`: RAG metadata + keyword index under `.v1` keys | Both are `.v2` info strings; the directory names (`chat_documents_meta_v1/`, `rag_keyword_v1/`) are historical and unchanged. | ✅ fixed (4 sites in CLAUDE.md) |
| `CLAUDE.md`: `verifyPassword(): Boolean` ordering / panic-PIN session gate | The live contract is `SecurityManager.verifyPassword(): VerifyResult`; the panic PIN gates on `securityMode == APP_PASSWORD`, and a live-session check was deliberately removed (documented race under `ON_STOP`). | open |
| `CLAUDE.md`: minSdk 29 in the island `recycle()` note | `:app` is `minSdk 31`. | ✅ fixed (line 11 now states 31) |
| `CLAUDE.md` full Dynamic-Island sections | The service entries had been commented out of `app/src/main/AndroidManifest.xml`. **Re-enabled** — `IslandOverlayService`, `IslandAccessibilityService`, and the `SYSTEM_ALERT_WINDOW` permission are all live again. | ✅ fixed |
| `CLAUDE.md`: "49+ instrumented tests" | 52 in `:app` alone; 68 in `:hxs`; 48 in `:hxs_encryptor`. | open |
| `CLAUDE.md` / `README`: no CI mention | There is no CI, no lint config, no detekt/ktlint/spotless, no git hooks. | open |

### Undocumented subsystems — live in the code, absent from `CLAUDE.md`

These ship and are compiled, but `CLAUDE.md` has **zero** mentions of any of them. Don't assume
they don't exist because the project memory is silent; read the source before changing them, and
consider adding a `CLAUDE.md` section when you touch one. (`tn_security` got its section;
the rest are still open.)

`VlmVisionCacheRepository`, `StorageInspector`, `RagPreferences`, `RagRaptor`,
`PluginPrefsRepository`, `RepositoryDataStore`,
`ModelConfigScreen`,
`RepositorySettings`, `GuidePluginsScreen`, `GuideImagesScreen`, and the
`com.dark.tool_neuron.plugin.*` web-plugin system (`PluginModule`, `WebPlugin`,
`PluginHubScreen`, `ToolDef`).

---

## 14. When to update the docs

Update `CLAUDE.md` in the **same change** when you touch:

- Security architecture or threat model
- Any auth flow or API surface (`SecurityManager`, `SessionHolder`, `PolicyEngine`,
  `AuthNative`, `BootIntegrity`)
- Any sealed state layout (`AuthState`, native manifest, license blob)
- New feature IDs or reshuffling of the `PRO_*` range
- New persistent HXS keys, vault directories, or HKDF info strings
- New integrity checks, obfuscation schemes, or crypto primitives
- New DI bindings touching the security graph
- Release-build hardening (ProGuard, signing, manifest flags)
- Anything in "Things still deferred" moving in or out of scope
- Any new "Things NOT to regress" item discovered along the way

Update this `AGENTS.md` when the build commands, module list, stack versions, AAR inventory,
or known traps change.
