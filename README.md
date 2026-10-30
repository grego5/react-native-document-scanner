# React Native Document Scanner

Android document scanning for React Native: live corner guidance, photo capture, crop editing, filters, zoomable previews and PDF export. Processing runs offline.

## Install

Requires React Native 0.78 or newer and Android 7.0 (API 24) or newer. Expo apps need a development build; Expo Go is not supported.

```sh
npm install react-native-document-scanner react-native-nitro-modules
```

Rebuild your Android app after installing. The library includes the camera permission declaration and a bundled detection model.

## Capture a page

Request permission, wait for the camera to be ready, then capture, detect and render a page:

```tsx
import { useRef, useState } from 'react'
import { Alert, Button, Linking, View } from 'react-native'
import {
  Camera,
  DocumentScanner,
  useCameraPermission,
  type CameraRef,
} from 'react-native-document-scanner'

export function Scanner({ onPage }: { onPage: (uri: string) => void }) {
  const camera = useRef<CameraRef>(null)
  const permission = useCameraPermission()
  const [ready, setReady] = useState(false)
  const [busy, setBusy] = useState(false)
  const reportError = (error: unknown) => Alert.alert('Scanner', String(error))

  async function capture() {
    if (!camera.current || busy) return
    setBusy(true)
    try {
      const photo = await camera.current.takePhoto()
      const analysis = await DocumentScanner.analyzePhoto(
        photo.originalUri, photo.liveCandidate?.quad,
      )
      if (!analysis.candidate) throw new Error('No page found. Try again.')
      const page = await DocumentScanner.renderDocument(
        photo.originalUri, analysis.candidate.quad,
        { paperSize: { width: 21, height: 29.7 }, dpi: 200 },
      )
      onPage(page.outputUri)
    } catch (error) {
      reportError(error)
    } finally {
      setBusy(false)
    }
  }

  if (permission.status !== 'granted') {
    return <Button title="Allow camera" onPress={() => {
      const request = permission.status === 'blocked' ? Linking.openSettings() : permission.request()
      void request.catch(reportError)
    }} />
  }

  return (
    <View style={{ flex: 1 }}>
      <Camera ref={camera} quadTensorModel="asset:///doccornernet/model.tflite" style={{ flex: 1 }}
        onCameraStateChange={(state) => setReady(state === 'ready')} />
      <Button title={busy ? 'Processing…' : 'Capture'} disabled={!ready || busy} onPress={capture} />
    </View>
  )
}
```

Capture is user-triggered. By default, the shutter waits for focus and steady framing. Photo analysis finishes before rendering, so filters run once on the final crop.

Photos, rendered pages and thumbnails are cached files. Save or copy anything you need to keep; call `DocumentScanner.removeCachedImages(uris)` when finished with the library's cached images.

## Review and export

Use `ImageViewer` for one page or `ImagePager` for several. Both support pinch zoom, pan and double tap.

```tsx
import { ImagePager } from 'react-native-document-scanner'

<ImagePager uris={pageUris} index={0} style={{ flex: 1 }} />
```

For manual cropping, pass the original photo and an editable quad to `CropEditor`. Keep edits in state and call `renderDocument` after the user confirms them.

```tsx
import { CropEditor } from 'react-native-document-scanner'

<CropEditor originalUri={photo.originalUri} quad={quad}
  onQuadChange={setQuad} style={{ flex: 1 }} />
```

Create a PDF from rendered page URIs. Supply an absolute output path or `file://` URI in an existing writable folder:

```ts
const pdfUri = await DocumentScanner.createPdf({
  outputPath,
  pages: pageUris,
  pageWidth: 595.28,
  pageHeight: 841.89,
  imageFit: 'contain',
  targetDpi: 200,
})
```

The host app handles sharing, saving and navigation. A complete scanner UI is in [`example/`](example/).

## Advanced features

### Camera options

| Option | Purpose |
| --- | --- |
| `mode="off"` | Release the camera while keeping the view mounted. |
| `mode="camera"` | Use the general camera profile instead of the default scanner profile. |
| `guide={false}` | Disable live document guidance. |
| `adaptiveDetection` | Pause inference while tracking a detected page. Default: off. |
| `squareFrame` | Crop preview, analysis and capture to 1:1. |
| `torch="auto"` | Automatically light dark scenes. Also accepts `on` or `off`. |
| `qualityGate={false}` | Capture without the scanner's focus and stability gate. |
| `captureFeedback={false}` | Disable the built-in capture animation and haptic feedback. |
| `retainCapturePreview` | Keep the shutter frame until your review image is ready. Clear it after loading. |

For a one-shot bypass (for example, on a long press), use `camera.current.takePhoto({ skipQualityGate: true })`. It skips document, focus, lighting and motion waits without changing camera settings. Camera readiness and permission checks still apply.
| `showStats` | Show status, model score and timings above the preview. |

Use `onCaptureStage`, `onCameraStateChange` and `onDocumentStateChange` for custom capture UI. Set `mode="off"` when the scanner screen is inactive.

### Rendering options

- `filter`: `none` (default), `enhance`, `grayscale` or `blackWhite`. `blackWhite` is high-contrast grayscale, not binary.
- `removeShadows`: enabled by default.
- `followPageEdges`: enabled by default; follows curved page edges. Disable it for a straight quad crop.
- `paperSize`: width and height in centimetres; `dpi` defaults to 200. Rendering preserves the crop's proportions without enlarging source pixels.
- `format`: `jpeg` (default) or `png`; PNG output is grayscale. `jpegQuality` accepts 0–1.

Each render returns `outputUri` and `thumbnailUri`. Always render edits from the original photo.

### Custom corner models

Provide the required model source as a camera prop:

```tsx
<Camera
  quadTensorModel={localModelUri}
  style={{ flex: 1 }}
/>
```

Download, cache, report progress and verify hashes in your app first. The camera stays off until the local model validates and warms up. A missing source or failed load emits `onError({ code })` and keeps the camera suspended. Changing the source suspends the camera until the replacement is ready; there is no automatic fallback. Native owns loading and readiness; matching camera requests reuse the prepared model. Use the same model across mounted cameras because inference is shared process-wide.

Input width and height are inferred from the model’s float32 `[1, height, width, 3]` tensor, including rectangular inputs. The output must be float32 `[1, 9]`. To use the bundled example model, explicitly pass `'asset:///doccornernet/model.tflite'`.

Supported sources are absolute local paths, `file://`, `content://`, `android.resource://`, `asset:///path`, raw resource names and packaged `file:///android_res/...` URIs. HTTP(S) sources are rejected. Models are limited to 128 MiB; loaded bytes remain owned by the module.

### Errors and diagnostics

Operations reject with `ScannerError` and a stable `code`, such as `capture/focus-timeout`. View errors arrive through `onError`; the host app chooses the message.

To collect samples for labelling and training, call `DocumentScanner.pickSampleFolder()`. While collection is on, `cameraRef.saveLiveSample()` saves the latest live inference frame and `DocumentScanner.saveCaptureSample(originalUri, quad)` saves a photo with the outline the user accepted and its last live frame before the shutter. Each image gets a JSON entry beside it that [`tools/label-samples`](tools/label-samples) opens directly. `DocumentScanner.setSampleCollection(false)` stops collecting but keeps the folder; `clearSampleFolder()` forgets it.

`Camera.minCornerScore` defaults to `0.95`. Lower live scores clear tracking and pause inference until image movement is measured; set `0` to disable score rejection. It adds no separate score check to capture or photo analysis; the existing quality gate still uses the accepted live document. Raw scores remain visible in statistics and saved samples.

Full prop and option documentation: [Camera](src/DocumentScannerCamera.nitro.ts), [CropEditor](src/DocumentCropEditor.nitro.ts), [ImageViewer](src/ImageViewer.nitro.ts), [ImagePager](src/ImagePager.nitro.ts), [processing and PDF](src/DocumentScannerProcessor.nitro.ts).

Use `DocumentScanner.setSampleFolder(fileDirectoryUri)` to select a private sample directory; `setSampleCollection(true)` enables collection.
