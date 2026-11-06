# iOS package scaffold

Requires iOS 18+, Nitro/Nitrogen 0.37.1, Swift 6 and C++20. All eight current
registrations are generated from TypeScript. Five views mount as empty UIKit
views. CameraPermission works independently of views; other operation result
unions return `feature/unsupported`, and unfinished synchronous methods throw.
The camera view streams a bounded rear-camera preview with live guidance and takes
stills into the bound scan session; capture gates and capture feedback are not
implemented yet.
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

The package is not release-ready. Package installation, Intel simulator and generic
arm64 device builds, actual registry construction, UIKit mounting and bundled model
integrity are verified; physical-device capture and guidance remain unverified.
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

## Camera session

`HybridDocumentScannerCameraView` owns one `AVCameraSession`: the rear physical
wide-angle camera, configured and started on its serial queue, with the preview layer
on main. The camera runs only while `mode` is not `off`, the view is in a window with
a size, the app is in the foreground and camera permission is granted. Losing any of
them stops it and keeps the view mounted; regaining them starts it again. `starting`
becomes `ready` on the first video frame and returns to `starting` while the system
interrupts the session. Each stop or restart advances a generation, so events of an
earlier start never publish. First-frame reporting is armed on the camera queue only
after the previous run has stopped and its queued frame callbacks have drained, so a
late frame of an earlier run cannot mark a restart ready.

Stills have a strict ceiling: at most 4096 pixels on the long side and 12,582,912
pixels in total, which excludes 24 and 48 MP modes. Among every format's advertised
`supportedMaxPhotoDimensions`, the largest bounded 4:3 size wins, then the largest
bounded size of another aspect. Among the formats offering it, a 4:3 video stream
comes first, then the video width nearest `analysisFrameWidth` (default 640) at or above
it, then below it. The photo output's `maxPhotoDimensions` is set to that size before
the session starts, and per-shot settings use the same size. A delivered photo may be
smaller, never larger. With no bounded configuration, or no camera, the view stays
`suspended`, reports `camera/unavailable` through `onError`, and does not retry until
the mode, request, attachment, foreground or permission changes.

The video-data output delivers BGRA, drops late frames and retains nothing past its
callback, so analysis never builds a backlog. Automatic buffer sizing is off, so it
delivers the active format's size rather than smaller preview-sized buffers; analysis
framing still comes from each delivered buffer's actual size, never the configured
format. Buffers stay in sensor axes:
`CameraFraming` names the clockwise turn (an EXIF orientation) and the centered crop
that make a buffer the upright frame shown. `squareAspectRatio` is virtual: the sensor
streams its full frame and only the centered square is shown and analyzed, so it
changes framing without restarting the camera. The preview layer is aspect-filled
into the framed region's aspect-fitted rectangle. Model tensor resizing stays inside
inference. Rotation follows `AVCaptureDevice.RotationCoordinator`.

The camera borrows its scan session: stopping, detaching or unmounting never closes
it. Replacing the session, or its close, advances the view's session generation for
capture publication.

## Still capture

`takePhoto` accepts one shot at a time; another while one is in flight rejects with
`capture/busy`. The quality gate does not exist yet, so an accepted shot fires at once and
`skipLowQualityControl` changes nothing. Acceptance freezes the bound session, correction and output options,
the model selection, the square framing, the still settings and the newest live outline
(only one seen through the framing the still is shot in, so not one from before a rotation
or square change; turning guidance off drops it), and asks live analysis for the next frame as the shutter frame. Capture state is
`unavailable`, `ready`, `shooting`, then `saving` once AVFoundation reports the exposure
complete.

Stills are native JPEG at the configured ceiling (smaller deliveries are accepted, larger
ones fail). `captureMode` sets the quality prioritization: `quality` (JPEG quality 1),
`latency` as speed (0.95), and the default `zsl` as balanced
with the photo output's zero-shutter-lag buffer (0.95), falling back to `latency` where
the configuration has none. Where JPEG is unavailable the still is uncompressed BGRA. The still is
tagged with the capture rotation live analysis uses. Mode and quality apply per shot
without restarting the camera; a request change (a restart) and a `squareAspectRatio` change wait
until the shot settles.

`AVCameraSession.capture` hands each request a self-retaining delegate that ends with one
terminal event on main, even if the camera stops meanwhile. The still then goes to the
session's image worker (`StillShot`): ImageIO decodes it once, the centered upright square
is cropped when framed square, and `SourceStore.publish` writes the provisional JPEG at the
same quality with orientation normalized, dimensions corrected and capture metadata kept;
no embedded thumbnail is written. JPEG is therefore encoded twice; avoiding that, and
measuring TIFF/BGRA, wait for physical-device samples. The shutter frame is written upright
as the page's retained frame, straightened to the live outline when it was detected on the
same framed size and orientation (a rotation between acceptance and the frame leaves it
upright but unstraightened), and is the page's thumbnail until a render. The live outline is mapped to
the still (both centered on the sensor, sharing its long side) and seeds analysis.

Publication is claimed through the shot's ticket: admission into the session runs only
while the ticket is unsettled, and settling is exactly once. Replacing the session rejects
the shot with `processor/superseded`, its close with `processor/disposed`, and any camera
stop (detaching, backgrounding, mode `off`, losing permission, unmounting) with
`camera/unavailable`, immediately; the camera restarts only after the shot settles, and the physical shot keeps the busy slot until AVFoundation
finishes and the worker has written nothing, admitted the page, or retired what it wrote.
The promise resolves with the page ID after admission; no source path reaches JavaScript.

The camera starts only once `cornerDetectionModel` is prepared. `CornerModelLoader` reads an
absolute path, a `file://` URL or a main-bundle resource name on its own worker and
installs it in `CornerInference.shared`. Only the newest request may activate: the
loader checks it under its own lock on the inference queue, immediately before the
validated model becomes the selection, so an older model never replaces a newer
request's outcome. The active source is not reloaded. A new source stops the camera until it is ready; a
failure reports `onError`, sets the document state `unavailable` and keeps the camera
suspended until the source changes.

## Camera controls

`CameraControls` executes focus, torch and exposure decisions on main and sends each
hardware command to the camera's session queue, where commands apply in order after
configuration, under the device's configuration lock. Every command, completion and
focus observation belongs to one configured run: a stop retires them, and the device's
focus observations are invalidated with the run.

Focus follows Android's ownership rules through a Swift `FocusPolicy`. Continuous
autofocus and auto-exposure aim at the centre of the frame, where the guide holds a page,
whenever no scan holds the lens; there is no lens parking. A preview tap (when
`tapToFocus` is on and the camera is streaming) is converted by the preview layer to a
device point and starts one `.autoFocus` scan there, also metering exposure there, held
for 5 s; a capture scan is held until the capture ends and times out after 2 s. A shot
whose photo is still exposing refuses new scans, and a tap past its hold waits until the
shot settles, which also applies a torch change made during it. A capture may
restart its scan while it waits. A device that cannot scan to a point ignores taps, and a
capture scan on it settles at once with an unknown result.

AVFoundation reports less than Camera2. The session queue is the single source of device
facts: commands apply there and report completion to main from there, and a focus change
notification only asks the queue to sample the device, so main receives samples and
completions in the order the queue saw them. A scan ends at the first sample after its own
command's completion that shows focus mode `.locked`: applying the scan leaves the device in
`.autoFocus` until the scan ends, and every sample delivered before the completion, such as an
earlier scan finishing while the command waited, was taken before it applied. That end says the scan finished,
not that it focused, so every scan settles with an unknown result. Focus observations are stamped on
the host clock when they reach the adapter; they bound the transition, they are not its
time. `onAutofocusChange` reports `scan` while `isAdjustingFocus` and `inactive` otherwise;
iOS never reports `focused`, `unfocused` or `locked`.

Frame times are converted from the capture session's clock to the host clock (mach
absolute time, the domain of `DispatchTime` uptime), so frames, focus observations and
deadlines compare directly. Each frame's exposure time and ISO come from its EXIF
attachment; without one, the device's current exposure duration and ISO stand in, marked
estimated. The auto torch is a Swift `TorchPolicy` matching Kotlin's, checked against the
shared `torch.tsv`: exposure-ms times ISO of at least 20,000 sustained for one second
lights it, and it stays lit until the camera stops or the mode changes. A frame without an
exposure restarts the wait, as a bright one does. Frames are admitted only while a run is
bound, so a frame delivered after a stop cannot start the next run's wait. Torch changes wait
while a capture is pending. Cameras without a torch ignore the mode.

`exposureCompensation` is clamped to the device's target-bias range (AVFoundation's bias
is continuous, so there is no step). `minFrameRate` chooses, within the active format's
frame-rate intervals, the lowest minimum at or above it (the highest reachable minimum when
none reaches it) and the maximum nearest 30, then sets the frame durations; 0 restores the
format's own. Both apply without restarting the camera.

## Live guidance

`LiveDocumentDetector` analyzes each frame of the armed run synchronously on the
camera's frame queue, its serial analysis owner, and borrows the buffer only for that
call. The camera drops late frames meanwhile, so no buffer queues or is retained:
flow keeps its own downscaled gray copy, and a single-slot frame request copies the
next framed region (packed BGRA rows, raw axes, with its EXIF orientation) for a
later shutter thumbnail. Each frame is framed from its delivered size, the capture
rotation angle and the square setting, so orientation, square crop and padded rows
reach both inference and flow unchanged.

Guide acceptance, expiry, flow retention, score rejection and the movement wake come
from the shared core through `ScannerLiveTracking` (the guide stabilizer, the flow
estimator and its transform history). Pacing is `AdaptiveDetectionPolicy`, a Swift
port of Android's, checked against the shared `adaptive.tsv` fixture. As on Android,
inference skips frames moving faster than 5% of the buffer width per second or with
a lost page region, waits 60 ms between inferences while a page is held, pauses
entirely while reliable flow carries a confirmed page when `inferenceThrottling` is on,
and pauses after a low-score frame until flow measures movement. Frames that skip
inference may still keep the guide through flow.

Results are immutable `LiveGuideResult` snapshots: status, the normalized upright
corners with the framing it was accepted on, acceptance ID and hold deadline, detector
and model generations, frame sequence, sensor timestamp and score. They are coalesced
to the newest and delivered on main, which drops any from an earlier generation (a
start, stop, guide toggle, score change or model change advances it), any already
past its deadline, and any whose acceptance already expired. Main expires a guide at
its 400 ms deadline. A replaced model publishes `searching` and starts the guide
over; acceptance IDs stay monotonic across resets. Inference failure reports
`unavailable` until the next start. The interpreter is opened and warmed off main
when guidance starts, and its lease is released when the camera stops. A lease is
acquired outside the detector's lock, because acquisition waits for the shared
inference queue, and is kept only while the camera still runs; an acquisition that
finishes after a stop is closed.

`guide` toggles guidance without restarting the camera. The document state is `off`
while the camera is stopped or the guide is off, then follows the results:
`searching`, `tracking`, `held` or `unavailable`. `LiveGuideOverlay` draws the guide
over the preview's presentation rectangle. AVFoundation's capture and preview rotation
angles can differ (a portrait-locked app held sideways), so each corner is mapped back
to buffer coordinates through the framing it was detected on and then into the
preview's own framing; a preview framing change clears the drawn guide. It is eased over `guideTransitionDuration`
(50 ms by default; jumps beyond a fifth of the view diagonal snap) in `guideColor`,
dimmed to 45% while held, and corner brackets around a centered A-series page while
searching. An invalid `minCornerScore` reports `input/invalid` and uses 0.95.

## Crop editor

`HybridDocumentCropEditorView` binds a session page's original through
`SessionPageBinding` and keeps the unsubmitted draft in `CropEditorView`. Corner
and edge moves, clamping and validity come from the shared crop geometry through
`ScannerCropGeometry`; filter previews come from the shared filters through
`ScannerDocumentRenderer.filterPreview`, on the session image worker. Methods
arriving from JavaScript run on main. Unmount, page removal and session close
invalidate the binding, pending reads and the draft; only `submit` mutates the page.
Clearing the photo, on detach, unmount or removal, cancels the touch sequence in
progress, and an abandoned drag returns to its starting corners. A saved crop the editor
cannot edit is reported as invalid input; the editor shows its clamp as a
suggestion, and reading or submitting the draft fails until an edit or preset
replaces it.

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
