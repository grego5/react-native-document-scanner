# React Native Document Scanner

Android document scanning for React Native: live corner guidance, photo capture, crop editing, filters, zoomable previews and PDF export. Processing runs offline.

## Install

Requires React Native 0.78 or newer and Android 7.0 (API 24) or newer. Expo apps need a development build; Expo Go is not supported.

```sh
npm install react-native-document-scanner react-native-nitro-modules
```

Rebuild your Android app after installing. The library includes the camera permission declaration and a bundled detection model.

## Start a scan

A session holds the scanned pages, their processing and their files. Create it in an effect, not during render, and close it on cleanup. Views only borrow it; navigation and selection stay in React.

```tsx
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Alert, Button, Linking, View } from 'react-native'
import {
  Camera, DocumentScanner, ImagePager, useCameraPermission,
  type CameraCaptureState, type CameraRef, type ScanSession,
} from 'react-native-document-scanner'

export function Scanner() {
  const [session, setSession] = useState<ScanSession | null>(null)
  useEffect(() => {
    const owner = DocumentScanner.createSession()
    setSession(owner)
    return () => { void owner.close().catch(console.warn) }
  }, [])
  return session ? <Scan session={session} /> : null
}

// This view borrows the session. Unmounting it does not dispose the session.
function Scan({ session }: { session: ScanSession }) {
  const camera = useRef<CameraRef>(null)
  const permission = useCameraPermission()
  const { pageIds, closed } = useSyncExternalStore(session.subscribe, session.getSession)
  const [captureState, setCaptureState] = useState<CameraCaptureState>('unavailable')
  const report = (error: unknown) => Alert.alert('Scanner', String(error))

  async function capture() {
    try {
      await camera.current?.takePhoto()
    } catch (error) { report(error) }
  }

  if (permission.status !== 'granted') {
    return <Button title="Allow camera" onPress={() => {
      const request = permission.status === 'blocked' ? Linking.openSettings() : permission.request()
      void request.catch(report)
    }} />
  }
  return (
    <View style={{ flex: 1 }}>
      <Camera renderOptions={{ paperSize: { width: 21, height: 29.7 }, dpi: 200, filter: 'none', removeShadows: true }}
        session={session} ref={camera} quadTensorModel="asset:///doccornernet/model.tflite"
        onCaptureStateChange={setCaptureState} onError={report} style={{ flex: 1 }} />
      <Button title="Capture" disabled={captureState !== 'ready' || closed}
        onPress={() => { void capture() }} />
      {pageIds.length > 0 && <ImagePager session={session} selectedPageId={pageIds[pageIds.length - 1]}
        style={{ flex: 1 }} />}
    </View>
  )
}
```

- `takePhoto()` waits for focus and steady framing, then resolves with the new page's ID. Detection and rendering continue in the background; page metadata reports `analyzing`, `rendering`, `ready` and failures. A page without a detected outline uses the whole photo.
- `Camera.renderOptions` sets the defaults for future captures. Edit saved pages with session methods.
- `pageIds` is the session's page order. `ImagePager` follows it unless you pass `pageIds`, which reorders the session; `session.setPageOrder(ids)` does the same without a view.
- `session.close()` cancels pending work and deletes the session's files; exported files are kept. A closed session can't be reused. To keep a scan across screens, hold the session in a longer-lived owner.

## Page thumbnails

Compose `PageThumbnail` inside your own tiles or grid. You own selection, labels and drag handling; finish a reorder with `setPageOrder`.

```tsx
import { useSyncExternalStore } from 'react'
import { FlatList, Pressable, Text } from 'react-native'
import { PageThumbnail, type ScanSession } from 'react-native-document-scanner'

function PageGrid({ session, selectedId, onSelect, beginDrag }: {
  session: ScanSession
  selectedId: string | null
  onSelect: (id: string) => void
  beginDrag: (id: string) => void
}) {
  const snapshot = useSyncExternalStore(session.subscribe, session.getSession)
  return (
    <FlatList data={snapshot.pageIds} keyExtractor={id => id} numColumns={3}
      extraData={{ selectedId, snapshot }}
      renderItem={({ item: id, index }) => (
        <Pressable onPress={() => onSelect(id)} onLongPress={() => beginDrag(id)}
          accessibilityRole="button" accessibilityLabel={`Page ${index + 1}`}
          accessibilityState={{ selected: id === selectedId }}>
          <PageThumbnail session={session} pageId={id} resizeMode="cover"
            style={{ width: 80, height: 100, borderWidth: id === selectedId ? 2 : 0 }} />
          <Text>{index + 1}{snapshot.pages[id]?.rendering ? ' (processing)' : ''}</Text>
        </Pressable>
      )} />
  )
}
```

`resizeMode` defaults to `cover`; use `contain` to show the whole page. Missing pages show an empty tile.

## Review and export

`ImageViewer` shows one page and `ImagePager` an ordered list; both support pinch zoom, pan and double tap. `ImagePager.direction` accepts `auto` (default), `ltr` or `rtl`.

```tsx
import { ImageViewer, ImagePager } from 'react-native-document-scanner'

<>
  <ImageViewer session={session} pageId={selectedId} style={{ flex: 1 }} />
  <ImagePager session={session} selectedPageId={selectedId} direction="auto"
    onPageChange={setSelectedId} style={{ flex: 1 }} />
</>
```

`CropEditor` edits a page's corners. It starts from the saved crop; `submit()` applies the edit, and unmounting discards it.

```tsx
import { useRef } from 'react'
import { CropEditor, type CropEditorRef } from 'react-native-document-scanner'

const cropEditor = useRef<CropEditorRef>(null)

<CropEditor session={session} pageId={selectedId} ref={cropEditor} style={{ flex: 1 }} />
```

```ts
await cropEditor.current!.setPreset('none') // No crop
await cropEditor.current!.setPreset('auto') // Detected outline; rejects if there is none
await cropEditor.current!.submit()          // Apply the edit to the page
await session.setFilter(selectedId, 'grayscale')
await session.setShadowRemoval(selectedId, false)
await session.removePage(selectedId)
```

`onPresetChange` reports whether the draft is `none`, `auto` or `custom`, for toggle buttons. Custom controls can read `cropEditor.current.getQuad()` or set corners with `session.setDocumentCrop(pageId, corners)`. Edits apply immediately; the page keeps showing its previous image until the new render is ready.

Export to an existing writable location:

```ts
const pdfUri = await session.createPdf({
  outputPath, // Absolute path or file:// URI; parent must already exist.
  pageWidth: 595.28, pageHeight: 841.89, imageFit: 'contain', targetDpi: 200,
})
const imageUris = await session.exportImages(outputDirectory)
```

Exports use the pages as they are when called; later edits don't change them. Pass page IDs first for a subset, a custom order or repeated pages. A failed export writes nothing. Exported files belong to your app: use unique destinations and delete them when done.

Sharing, saving, navigation and layout are up to your app. See [`example/`](example/) for a complete app.

## Advanced features

### Camera options

| Option | Purpose |
| --- | --- |
| `mode="off"` | Release the camera while keeping the view mounted, e.g. when the screen is inactive. |
| `mode="camera"` | Use the general camera profile instead of the default scanner profile. |
| `guide={false}` | Disable live document guidance. |
| `adaptiveDetection` | Pause inference while tracking a detected page. Default: off. |
| `squareFrame` | Crop preview, analysis and capture to 1:1. |
| `torch="auto"` | Automatically light dark scenes. Also accepts `on` or `off`. |
| `qualityGate={false}` | Capture without waiting for focus and stability. |
| `captureFeedback={false}` | Disable the built-in capture animation and haptic feedback. |
| `minCornerScore` | Lowest live detection score accepted; default `0.95`, `0` accepts all. |
| `showStats` | Show status, model score and timings above the preview. |

To skip the quality gate once (for example, on a long press), call `camera.current.takePhoto({ skipQualityGate: true })`.

`onCaptureStage(stage, waitReason)` reports why capture is waiting (`document`, `focus`, `light`, `steady`), so you can show your own instructions; the camera draws no text. `onCaptureStateChange`, `onCameraStateChange`, `onDocumentStateChange` and `onError` cover the rest of the UI.

### Rendering options

- `filter`: `none` (default), `enhance`, `grayscale` or `blackWhite`. `blackWhite` is high-contrast grayscale, not binary.
- `removeShadows`: enabled by default.
- `followPageEdges`: enabled by default; follows curved page edges. Disable it for a straight crop.
- `paperSize`: width and height in centimetres; `dpi` defaults to 200. The page keeps its proportions and is never enlarged past the photo's pixels.
- `format`: `jpeg` (default), `png` or `auto`. `auto` uses 4-bit grayscale PNG for `blackWhite` and JPEG otherwise. `png` quantizes to 16 gray levels. `jpegQuality` accepts 0–1.

### Custom corner models

Pass a local model to the camera:

```tsx
<Camera session={session} quadTensorModel={localModelUri} style={{ flex: 1 }} />
```

Download and verify the model in your app first. The camera stays off until the model loads; a missing or invalid model emits `onError` and keeps it off, with no fallback. All mounted cameras share one model, so use the same source. The bundled model is `'asset:///doccornernet/model.tflite'`.

The model takes a float32 `[1, height, width, 3]` input and returns float32 `[1, 9]`. Sources can be local paths, `file://`, `content://`, `android.resource://`, `asset:///` or raw resource names, up to 128 MiB; HTTP(S) is rejected.

### Errors

Operations reject with `ScannerError` and a stable `code`, such as `capture/focus-timeout`; view errors arrive through `onError`. An operation replaced by a later one rejects with `ScannerCancellation` (`name === 'AbortError'`) instead, which handlers can ignore:

```ts
import { ScannerCancellation } from 'react-native-document-scanner'

try {
  const pageId = await camera.current!.takePhoto()
  select(pageId)
} catch (error) {
  if (error instanceof ScannerCancellation) return
  report(error)
}
```

### Training samples

To collect images for labelling with [`tools/label-samples`](tools/label-samples), pick a folder with `DocumentScanner.pickSampleFolder()` (or `setSampleFolder(directoryUri)` and `setSampleCollection(true)`). Then `session.saveCaptureSample(pageId)` saves the image corner detection used for that page, with the model's outline. `setSampleCollection(false)` pauses collection; `clearSampleFolder()` forgets the folder.

## API reference

Full prop and option documentation: [Camera](src/DocumentScannerCamera.nitro.ts), [CropEditor](src/DocumentCropEditor.nitro.ts), [ImageViewer](src/ImageViewer.nitro.ts), [ImagePager](src/ImagePager.nitro.ts), [render options](src/DocumentScannerProcessor.nitro.ts), [session commands and PDF](src/ScanSession.nitro.ts).
