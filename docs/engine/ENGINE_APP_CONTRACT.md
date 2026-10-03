# Engine ↔ App Contract

**Audience:** maintainers of this private app and reviewers of engine bumps.

The engine is published as [`sempermechanics/semper-dic-engine`](https://github.com/sempermechanics/semper-dic-engine)
(C/C++ SDK + Android JNI + Python). This app links it as a **pinned git submodule** at `engine/`.
The public copy of this contract lives at `engine/docs/CONTRACT.md` — the engine repo's own
copy of it; keep the two in sync
when Frozen/Stable rules change.

> **The rule in one sentence:** anything in the tables marked **Frozen** or **Stable** below is
> load-bearing for a downstream app; change it carelessly and you break a shipping product whose
> code you can't see. The [golden contract test](#a6-how-improvements-reach-the-app) is the
> automated backstop; this document is the human one.

You do **not** need the app to contribute. Build and test against `engine/tests/` as usual. Just
respect the surface described here, and declare your change's [tier](#a1-stability-tiers) in the PR.

---

## A.1 Stability tiers

| Tier | What it covers | Rule for contributors |
|---|---|---|
| **Frozen** | Binary/data formats: output packing, metrics layout, error codes, and the *meaning* of existing `FullFieldParams` fields | Never change in place. A change here is a **major** version bump and requires a coordinated change in the downstream app. |
| **Stable** | Public function **signatures** in `include/semper/{pipeline,io,cancel,version}.hpp` | May be **extended additively** (new overloads / new functions). Existing signatures, parameter order, defaults, and semantics must not change without a **major** bump. |
| **Additive-only** | Deliberate growth points: trailing `FullFieldParams` fields, unused metrics slots | Append only, each with a default that reproduces prior behavior. Never reorder or repurpose an existing entry. |
| **Internal** | Everything in `src/`, `tuning.hpp` constants, `PointState`, `types.hpp`, algorithm internals, private headers | Change freely. Behavior is guarded by the golden test, not by this contract. |

**Semantic versioning:**
- **major** — any Frozen or Stable break.
- **minor** — additive Stable / Additive-only change, or a new capability.
- **patch** — Internal-only improvement (accuracy, speed, robustness) with no surface change.

The app pins an **exact** engine tag and only auto-adopts minor/patch bumps.

---

## A.2 Public API reference (the app-facing surface)

These are the **only** engine symbols the app calls (through its private JNI adapter). Keep the
signatures here in sync with the code — they are authoritative.

### `pipeline::run_full_field` — the solve · *Stable; behavior Frozen via golden test*

```cpp
// include/semper/pipeline.hpp
int run_full_field(ReferenceCache& cache,
                   const cv::Mat& def_gray,
                   const cv::Mat& roi_mask,
                   const FullFieldParams& params,
                   float* output_ptr, int output_capacity,
                   float* metrics, int metrics_len,
                   ProgressCallback on_progress = nullptr);
```

- **Returns** *(Frozen)*: `>= 0` → number of valid output points. `-2` → invalid ROI. `-3` →
  init/argument failure. `-99` (`kCancelled`) → cancelled mid-solve.
- **Writes** *(Frozen)*: at most `output_capacity` floats, **8 per point** (see [A.4](#a4-frozen-data-formats-the-highest-risk-break-points)).
  Points beyond capacity are **dropped, never overflow** — do not weaken this guarantee.
- **Metrics** *(Frozen layout)*: when `metrics != nullptr && metrics_len >= 16`, fill telemetry;
  17 slots preferred. Slot indices are frozen.
- **Cancellation**: clears the cancel flag on entry, then polls it inside the point loops so a
  cancel lands within a point or two.
- **You may** make it faster or more accurate however you like (that is Internal behavior, guarded
  by the golden test). **You may not** change the return-code meanings, the 8-float packing, the
  capacity-drop rule, or the metric slot indices without a major bump. Adding a *new* overload
  (e.g. one taking a `CancelToken&`) is additive and encouraged.

### `pipeline::ReferenceCache` — cached reference state · *Stable*

```cpp
// include/semper/pipeline.hpp
struct ReferenceCache {                 // non-copyable, non-movable
    ReferenceCache();
    void reset();
    void set_from_gray(const cv::Mat& gray_in, const cv::Mat& roi_mask);
    std::string debug_dir;              // app sets an on-device path; "" disables debug export
    // internal members (Image* ref_img, akaze_*, mutex, ...) are INTERNAL
};
```

- The app's surface is exactly `reset()`, `set_from_gray(cv::Mat, cv::Mat)`, and the `debug_dir`
  field — keep their names and signatures. Internal members may change freely.
- Must remain **non-copyable / non-movable**: the app (and JNI) rely on a single stable instance
  whose address does not move.

### `pipeline::FullFieldParams` — solve inputs · *Frozen fields + Additive-only tail*

```cpp
// include/semper/pipeline.hpp
struct FullFieldParams {
    int rect_x, rect_y, rect_w, rect_h;   // ROI in pixels
    int step;                             // grid spacing
    int subset_size;                      // correlation subset (px)
    int strain_window;                    // VSG strain window
    bool use_6x6_interpolator;            // interpolation-kernel toggle
};
```

- The existing fields' names, types, and meanings are **Frozen** — the JNI adapter marshals them
  positionally from Kotlin.
- New tunables must be **appended** with a default that reproduces current behavior when unset.
  Never insert or reorder.

### `pipeline::ProgressCallback` — progress reporting · *Stable*

```cpp
// include/semper/pipeline.hpp
using ProgressCallback = std::function<void(int percentage)>;   // 0..100
```

- Invoked from the solve thread. Keep the `void(int)` shape; the app hops it onto its UI thread.

### Cancellation — `include/semper/cancel.hpp` · *Stable*

```cpp
constexpr int kCancelled = -99;   // Frozen value
void request_cancel();            // thread-safe; may be called during a solve
void clear_cancel();
bool cancel_requested();
```

- These free functions must keep working — the JNI `setCancelRequested` maps directly to them.
  The instance-token refactor **adds** a `CancelToken` overload of `run_full_field` but retains
  these as a thin shim over a default process token.

### Image I/O — `include/semper/io.hpp` · *Stable*

```cpp
cv::Mat decode_gray(const uint8_t* data, size_t len, int expected_w = 0, int expected_h = 0);
cv::Mat decode_bgr (const uint8_t* data, size_t len);           // preview; empty on failure
void    image_dimensions(const uint8_t* data, size_t len, int& out_w, int& out_h);
```

- `decode_gray` accepts **encoded bytes** (PNG/JPEG) *or* a **raw RGBA / ALPHA_8 buffer** when
  `expected_w`/`expected_h` are supplied — keep both paths.
- An **empty `cv::Mat` means failure** (the app checks `.empty()`); do not switch to throwing.

---

## A.3 The JNI mapping (informative)

JNI C++ lives in the public engine (`adapters/android/`); Kotlin `SemperNativeLib` stays in this
app. All JNI / OpenMP work runs on a single pinned daemon thread (`nativeDispatcher`) because
OpenMP on Android aborts if driven from varying threads.

| JNI export | Public engine calls used |
|---|---|
| `JNI_OnLoad` | `cv::setNumThreads(1)` |
| `setDebugOutputDir(String?)` | `ReferenceCache::debug_dir` |
| `setCancelRequested(bool)` | `request_cancel` / `clear_cancel` |
| `getImageDimensions(byte[])` | `io::image_dimensions` |
| `getPreviewFromBytes(byte[], int)` | `io::decode_bgr` |
| `initializeReference(byte[], byte[]?, int, int)` | `io::decode_gray`, `ReferenceCache::set_from_gray` / `reset` |
| `computeFullFieldDirect(…)` | `io::decode_gray`, `run_full_field` |

The full engine surface the app depends on is therefore small and closed:
`run_full_field`, `ReferenceCache` (`reset` / `set_from_gray` / `debug_dir`), `FullFieldParams`,
`ProgressCallback`, the three cancel functions + `kCancelled`, and the three `io::` decoders.
**Everything else in the engine is Internal.**

---

## A.4 Frozen data formats (the highest-risk break points)

### Output buffer — packed `float32`, **8 per point**

```
index:  0   1   2   3     4     5     6      7
field:  x   y   u   v    exx   eyy   exy   corr
```

`output_capacity` is measured in **floats**; the solver drops any points past it. Reordering,
resizing the stride, or repurposing a slot is a **major** break — the app parses this layout
verbatim by stride.

### Metrics buffer — **17 × `float32`**

Layout is defined by `EngineStats.fromArray` in the app. Slot **indices are Frozen**; new
telemetry appends at the next free slot (Additive-only). `metrics_len == 16` is the minimum
honored; 17 is preferred.

### Return / error codes — Frozen

| Value | Meaning |
|---|---|
| `>= 0` | number of valid output points |
| `-2` | invalid ROI |
| `-3` | init / argument failure |
| `-99` (`kCancelled`) | cancelled mid-solve |

---

## A.5 Change checklist for contributors

**✅ Free to change — patch, no app impact.** Correlation math, ICGN solver, AKAZE seeding, SIMD
kernels, threading, memory strategy, `tuning.hpp`, anything under `src/` — as long as the golden
test still passes within tolerance.

**⚠️ Additive only — minor.** A new trailing `FullFieldParams` field (with a safe default); a new
metrics slot; a new function or overload; a new SDK entry point.

**⛔ Do not — without a major bump *and* coordination with the app maintainer.** Change any
signature in [A.2](#a2-public-api-reference-the-app-facing-surface); change the 8-float packing or
its order; change the meaning of a metrics slot; change a return-code value or meaning; make the
`decode_*` functions throw instead of returning empty; make `ReferenceCache` copyable or movable.

> Every PR that touches `include/semper/*` must state its tier (**patch / minor / major**) in the
> description and update this document in the same change.

---

## A.6 How improvements reach the app

1. **Land** the change in [`sempermechanics/semper-dic-engine`](https://github.com/sempermechanics/semper-dic-engine)
   behind the same public signatures (or additively). Update `SEMPER_VERSION` per the semver rules
   in [A.1](#a1-stability-tiers).
2. **Tag** a release. This app bumps its **pinned `engine/` submodule tag**, a maintainer reviews
   the engine diff, and app CI runs the **golden contract test**: a checked-in synthetic
   reference/deformed pair is solved through the real entry point, and the resulting `(N, 8)`
   field plus key metric slots are asserted against a stored golden within tolerance.
   - A signature change fails to **compile**.
   - A behavior change fails the **golden test**.
3. **Ship.** The app links the engine statically, so *source compatibility* of `include/semper/*`
   is what matters day to day. The C SDK's `semper_c.h` additionally guarantees *ABI* stability for
   any external (non-app) consumer.

**Free wins for the app** — patch/minor, no app code required: faster or more robust ICGN, better
seeding, SIMD, accuracy improvements. **Needs app work** — major, or a new-capability minor: extra
per-point outputs, new solve modes or parameters. Those are additive in the engine **plus** new
plumbing in the app to surface them.
