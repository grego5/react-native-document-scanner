# Host integration

Install `react-native-document-scanner` and `react-native-nitro-modules` and rebuild the host. Expo requires a development build. See [iOS setup](ios/setup.md) for native dependencies and permissions. Public types are exported from `src/index.ts`.

## Document workflow

- Create a session with `DocumentScanner.createSession()` at a host lifetime boundary and close it when replaced or unmounted. Share it among `Camera`, `ImagePager`, `PageThumbnail` and `CropEditor`. The host owns navigation, selected page and permission UI via `useCameraPermission()`.
- `Camera` uses one native view. Document mode enables managed focus by default on Android. Photo mode disables live guidance and auto-crop. `active` controls camera use independently of session lifetime.
- `takePhoto()` returns an admitted page ID. Read order and readiness with `useSyncExternalStore(session.subscribe, session.getSession)`. Camera and import options apply to future pages. Edit admitted pages through session commands or a submitted crop draft. Export with `createPdf()` or `exportImages()` to a host destination. See [Session architecture](architecture.md).
- `importFromLibrary()` validates options before opening the system picker and admits readable picks in picked order. No library permission is required. Cancellation or no readable picks returns `[]`. A write failure rejects and keeps already admitted pages. A second picker rejects with `capture/busy`. Android's document-picker fallback takes the first `maxCount` results in picker order.
- Closing a session rejects a pending import with `processor/disposed` and waits for its picker slot to clear. iOS dismisses its picker. Android drops the eventual result. Android Activity destruction cancels with `AbortError`.
- For existing files, use `importImage` or `importImages`. Both platforms accept `file://`, and Android also accepts `content://`. Each file has its own admission outcome. Use the camera's `imageCorrection`, `outputOptions` and `autoCrop` defaults for matching output. Model readiness and crop precedence follow [session admission](architecture.md#admission-and-revisions).
- `camera/analysis-slow` is a non-blocking warning about delivered analysis frames. A healthy window or a new run rearms it. See [Android validation](android/validation.md) for diagnostics.

## Text reading

- Create `DocumentScanner.createTextSession()` when the scan starts, outside render. Pass it to one `TextCamera` at a time. A second attachment reports `input/invalid`. The host closes the session when the scan ends. The camera owns preview frames. The session owns the capture and status, so the capture survives a view remount.
- Read immutable snapshots with `useSyncExternalStore(session.subscribe, session.getSnapshot)`. The store ignores older versions. A snapshot keeps the published capture object while the still is unchanged, so a status change does not reset a selection. Capture publishes its result before resolving, and resolves with its own snapshot even if a listener clears it meanwhile. `clearCapture()` supports retake. `close()` clears the capture, rejects pending requests with `processor/disposed` and publishes the terminal snapshot.
- `textRegion` is normalized to the view from the top left. `session.capture()` puts a region crop and recognized words into the snapshot. The image is a grayscale JPEG data URI with no file to clean up. Word bounds and corners are normalized to that image. See [Android capture selection](android/text-camera.md#capture-selection) and the [iOS text camera](ios/camera.md#text-camera) for timeout and fallback rules.
- `status` drives the guide and host hints. Android precedence is `off → starting → capturing → blurred → focusing → ready`, highest first. iOS publishes `off`, `starting`, `capturing` and `ready`. Android also supports `zoom` and managed `focusLock`, enabled by the wrapper.
- `guide` styles the region box and label. Missing colors and labels use `ready` values, with amber as the default blur color. An empty label hides it. `off` and `starting` show no label. `guide={false}` hides native drawing. `onGuideLayout` still reports the clamped box in view points and includes the label when shown.
- `TextSelection` fits the image inside `imageInsets` at `imagePosition`. Gestures use view coordinates without RTL mirroring. A tap can pick a slanted word. A drag starts at the press point with its moving corner lifted away from the finger. `onSelectionChange` returns words and text grouped by line. `DocumentScanner.copyText()` writes to the clipboard.

## Corner detection model

- The package ships no model. Supply a float32 TFLite model via `cornerDetectionModel` or the Expo plugin. See the [engine model contract](engine.md#corner-model) for tensors and normalization.
- Both platforms accept `asset:///<path>`, absolute paths and `file://`. Android also accepts `content://`, `android.resource://` and raw resource names. iOS accepts main-bundle resource names.
- The Expo option resolves relative to the app root and copies the file into both apps. Missing files fail prebuild. After removing the option, run `expo prebuild --clean` to remove the copied model.
- One model selection is active app-wide. The first document session can load the plugin model without a camera. A camera that needs a model and omits `cornerDetectionModel` requires a packaged default. It follows the current selection, including a failed explicit request. A new explicit request can retry or replace that selection.
- `waitForCornerDetectionModel()` starts the packaged default if no model was requested. It returns true when active, false without a model, or rejects on load failure or supersession.
- A camera that needs live corners or auto-crop waits for a ready model and reports loading or unavailable via document state. Missing or invalid sources can report `input/invalid`. Failed loading or validation reports `model/load-failed`. The host can explicitly switch to photo mode, which works without a model.

## Example app

The example wires shutter readiness to the camera ref, Gallery to ordered import and Text to a separate scan session. `DOCUMENT_SCANNER_CORNER_MODEL` in ignored `example/.env` overrides the model at prebuild. See [Maintainer tools](tools.md#model-export-and-private-fixtures) for the default model and fixture provenance.
