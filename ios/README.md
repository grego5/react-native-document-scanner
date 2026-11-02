# iOS package scaffold

Requires iOS 18+, Nitro/Nitrogen 0.37.1, Swift 6 and C++20. All eight current
registrations are generated from TypeScript. Five views mount as empty UIKit
views. CameraPermission works independently of views; other operation result
unions return `feature/unsupported`, and unfinished synchronous methods throw.
Camera capture, session orchestration, inference and export remain scaffolds.
An internal source store is available for those later native implementations.

Add the pinned official OpenCV podspec inside the app target in the Podfile,
using the installed library path (adjust for the app's node_modules layout):

```ruby
pod 'DocumentScannerOpenCV', :podspec => '../node_modules/react-native-document-scanner/ios/DocumentScannerOpenCV.podspec'
```

React Native autolinks ReactNativeDocumentScanner. It depends on TensorFlowLiteC
2.17.0 and includes all portable core sources, generated registrations and the
canonical model in DocumentScannerModels.bundle. The model remains unmodified.

The official OpenCV 4.12.0 framework has arm64 iOS device and x86_64 simulator
slices. Build an Intel simulator or generic arm64 device; Apple Silicon simulator
support remains unverified and needs a separate platform-correct slice. Do not
substitute a device arm64 binary into a simulator build.

The package is not release-ready. Task 17 verified package installation, Intel simulator and generic arm64 device
builds, actual registry construction, UIKit mounting and bundled model integrity.
Functional iOS behavior remains for the following tasks.
Machine-specific hosts, build scripts and test runners stay outside this repo.

## Camera permission

The host app must supply a nonempty `NSCameraUsageDescription` in its Info.plist.
`CameraPermission` reads AVFoundation authorization without creating a camera.
iOS maps authorized to `granted`, notDetermined to `not-determined`, and
restricted/denied to `blocked` (only Settings can change it).

Requests share one process-wide prompt and require an active foreground app.
A missing usage description returns `permission/unavailable`; unavailable
foreground presentation returns `permission/request-failed`. Neither failure
attempts a system prompt. Already determined states return immediately without
requiring foreground presentation. Status subscribers refresh on app activation
and release their listener with the returned unsubscribe function. Callbacks and
permission state are owned by main; AVFoundation completion returns to main.
Native camera attachment/resume will consume this owner in the camera task.

## Source storage and pixel leases

`SourceStore.shared` is the process-wide internal source owner; camera, processor,
editor and image views will use it rather than owning a second cache. Session/page
IDs remain the public API. Sources have real local file URLs internally; no image
paths are added to JS session metadata. The iOS ImageIO layer accepts TIFF, JPEG
and PNG; Android keeps its current JPEG source loader and JPEG/PNG render views.

The store's serial source queue owns entries, publication, decode, file pins and
eviction. Call these synchronous APIs from a native worker. `publish` borrows a
capture CGImage only for the call, copies/normalizes EXIF and an optional integral
upright crop, and atomically moves a completed provisional JPEG into place. It
preserves supplied capture metadata while correcting dimensions and orientation.
The resident capture pixels avoid encoding/decoding before the first native read;
older pages reopen their immutable file. JPEG is lossy, so a later reopen has the
same geometry/content with JPEG quantization, rather than byte-identical raw pixels.
TIFF versus JPEG capture measurements remain deferred to the physical-device sample.

One full-resolution resident allocation and one reduced thumbnail (at most 512 px)
are cached. Immediate thumbnails can reuse resident capture pixels. The default
budget is 256 MiB; source dimensions are limited to 16384 per side and 32 million
pixels. Admission reserves pixel staging conservatively (three RGBA-size units,
covering a 64-bit decoded source and an encoding image) plus the output allocation.
The budget also counts all evicted allocations retained by Swift, CGImage or C++
leases. It fails immediately with a typed `processor/unavailable` payload when
admission would exceed the budget; callers retry after releasing leases. Codec
bookkeeping and the caller's borrowed capture buffers are outside this store's
allocation accounting. Larger captures may require a later measured budget policy.

A `SourcePixelLease` retains stable opaque sRGB RGBA8 and its file pin. Borrow its
bytes only within `withUnsafeBytes`; an ObjC++ adapter uses
`ScannerBorrowSourcePixels` with the lease as its retained NSObject owner. That
constructs the shared core's upright, full-crop PixelLease without copying pixels.
CGImages made from a lease also retain its storage/accounting and file pin.
Memory warnings enqueue eviction of both cache slots without waiting on the
posting thread; outstanding operations remain valid. Explicit `evict()` stays
synchronous for callers needing a completion boundary.

`remove` validates the entire request before retirement. Only registered files
published by this owner can be deleted; foreign files, directories and symlink
paths are rejected. Retirement immediately rejects new reads and defers physical
removal until file pins drain. Completed retirement drops the full entry and ImageIO metadata after the final
pin. A FIFO of at most 256 canonical URLs preserves idempotence for recent absent
files; older URLs lose ownership recognition and deletion is rejected. A file
recreated at a remembered retired URL is treated as foreign and is never deleted.
Session
cleanup must explicitly retire its resources; `shutdown` preserves durable backing,
marks closed immediately and rejects pending/new work with `processor/disposed`.
It then queues cache/observer/registry release; `drain` joins queued cleanup. Existing leases
remain valid and release buffers on their final native owner. Failed deferred
removal is logged and remains retryable through explicit retirement.

## Binding generation

Run `npm run nitrogen` in the repository. The version-guarded wrapper copies the
installed Nitrogen 0.37.1 generator to ignored `.local/` and adjusts its struct
equality emission before generation. Installed packages and generated output are
never edited in place by the wrapper.

`DOCUMENT_SCANNER_SWIFT_IMPORT`, set only through the pod's Swift Clang-importer
flags, hides generated defaulted equality operators from Swift. Normal C++ and
Android retain those operators. This addresses the importer failure tracked in
[Nitro #1186](https://github.com/margelo/nitro/issues/1186) and
[Nitro #1376](https://github.com/margelo/nitro/issues/1376). Revalidate and remove
the workaround when changing the pinned generator/toolchain. Xcode 26.6 / Swift 6.3.3 simulator and arm64 device builds, plus seven native
smoke tests, validated this workaround for the package.
