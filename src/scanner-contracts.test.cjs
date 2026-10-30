const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const ts = require('typescript')

// Execute the real pure adapters with injected native/filesystem boundaries; no React Native runtime is required.
function load(relative, imports = {}, clock = Date) {
  const source = readFileSync(path.join(__dirname, '..', relative), 'utf8')
  const code = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true },
  }).outputText
  const module = { exports: {} }
  const requireImport = (name) => {
    if (Object.hasOwn(imports, name)) return imports[name]
    throw new Error(`Unexpected dependency: ${name}`)
  }
  new Function('module', 'exports', 'require', 'Date', code)(module, module.exports, requireImport, clock)
  return module.exports
}

const errors = load('src/ScannerError.ts')

test('a typed native failure becomes a rejected ScannerError with a stable code', async () => {
  await assert.rejects(errors.scannerOperation(async () => ({ code: 'capture/focus-timeout' }), 'capture/failed'),
    (error) => error instanceof errors.ScannerError && error.code === 'capture/focus-timeout' &&
      error.message === 'capture/focus-timeout')
})

test('unexpected asynchronous and synchronous bridge failures retain their cause', async () => {
  const original = new Error('Platform diagnostic should not become display copy')
  const check = (error) => error instanceof errors.ScannerError && error.code === 'analysis/failed' && error.cause === original
  await assert.rejects(errors.scannerOperation(() => { throw original }, 'analysis/failed'), check)
  await assert.rejects(errors.scannerOperation(async () => { throw original }, 'analysis/failed'), check)
  assert.throws(() => errors.scannerValue(() => { throw original }, 'analysis/failed'), check)
})

test('the public processor facade unwraps results, rejects failures and preserves cleanup completion', async () => {
  const analysis = { sourceWidth: 640, sourceHeight: 480, duration: 12 }
  const calls = []
  const native = {
    analyzePhotoResult: async (uri, quad) => { calls.push([uri, quad]); return analysis },
    renderDocumentResult: async () => ({ code: 'render/failed' }),
    createPdfResult: async () => 'file:///unique.pdf',
    removeCachedImagesResult: async () => true,
    dispose: () => calls.push('disposed'),
  }
  const { DocumentScanner } = load('src/index.ts', {
    'react-native-nitro-modules': { NitroModules: { createHybridObject: () => native } },
    './ScannerError': errors,
    './DocumentScannerCamera': {},
    './DocumentCropEditor': {},
    './ImageViewer': {}, './ImagePager': {},
    './CameraPermission': {},
  })
  assert.equal(await DocumentScanner.analyzePhoto('file:///photo.jpg'), analysis)
  const liveQuad = { topLeft: { x: .1, y: .1 } }
  assert.equal(await DocumentScanner.analyzePhoto('file:///photo.jpg', liveQuad), analysis)
  await assert.rejects(DocumentScanner.renderDocument('file:///photo.jpg', {}, {}),
    (error) => error.code === 'render/failed')
  assert.equal(await DocumentScanner.createPdf({}), 'file:///unique.pdf')
  assert.equal(await DocumentScanner.removeCachedImages([]), undefined)
  assert.equal(DocumentScanner.setCornerModel, undefined)
  DocumentScanner.dispose()
  assert.deepEqual(calls, [['file:///photo.jpg', undefined], ['file:///photo.jpg', liveQuad], 'disposed'])
})

function pdfFixture() {
  const files = new Map()
  let now = 2_000_000_000_000
  class FakeFile {
    constructor(directory, name) { this.name = name; this.uri = `file:///cache/exports/${name}` }
    get exists() { return files.has(this.name) }
    delete() { files.delete(this.name) }
  }
  class FakeDirectory {
    create() {}
    get exists() { return true }
    list() { return [...files.values()] }
  }
  const api = { Directory: FakeDirectory, File: FakeFile, Paths: { cache: 'file:///cache' } }
  class Clock extends Date { static now() { return now } }
  return {
    load: () => load('example/lib/pdfExports.ts', { 'expo-file-system': api }, Clock),
    add: (name) => { const file = new FakeFile(null, name); files.set(name, file); return file },
    files,
    now: () => now,
    advance: (time) => { now += time },
  }
}

test('repeated exports and a new launch never reuse a previous share path', () => {
  const fixture = pdfFixture()
  const firstLaunch = fixture.load()
  const first = firstLaunch.newPdfExport()
  fixture.add(first.name)
  const second = firstLaunch.newPdfExport()
  fixture.add(second.name)
  const nextLaunch = fixture.load().newPdfExport()
  assert.notEqual(first.uri, second.uri)
  assert.notEqual(nextLaunch.uri, first.uri)
  assert.notEqual(nextLaunch.uri, second.uri)
  assert.equal(fixture.files.size, 2)
})

test('PDF cleanup retains recent shares and touches only recognized expired exports', () => {
  const fixture = pdfFixture()
  const day = 24 * 60 * 60 * 1000
  const recent = fixture.add(`scan-${fixture.now() - day}-1.pdf`)
  const expired = fixture.add(`scan-${fixture.now() - 8 * day}-2.pdf`)
  const future = fixture.add(`scan-${fixture.now() + day}-3.pdf`)
  fixture.add('unrelated.pdf')
  fixture.load().removeOldPdfExports()
  assert.equal(fixture.files.has(recent.name), true)
  assert.equal(fixture.files.has(expired.name), false)
  assert.equal(fixture.files.has(future.name), true)
  assert.equal(fixture.files.has('unrelated.pdf'), true)
})


test('shadow removal invalidates previews and persists when a page is reopened', () => {
  const review = load('example/lib/documentReview.ts')
  const { sessionReducer, isPreviewCurrent, INITIAL_SESSION, FULL_FRAME } = load('example/lib/scanSession.ts', { './documentReview': review })
  const draft = { id: 'd', pageId: null, originalUri: 'file:///original.jpg', analyzing: false, quad: FULL_FRAME,
    crop: false, filter: 'none', removeShadows: false, cropQuad: null, renders: [], preview: { uri: 'file:///original.jpg', quad: FULL_FRAME, filter: 'none', removeShadows: false } }
  let state = { ...INITIAL_SESSION, draft }
  assert.equal(isPreviewCurrent(state.draft), true)
  state = sessionReducer(state, { type: 'set-remove-shadows', value: true })
  assert.equal(isPreviewCurrent(state.draft), false)
  state = sessionReducer(state, { type: 'previewed', draftId: 'd', preview: { uri: 'file:///auto.jpg', quad: FULL_FRAME, filter: 'none', removeShadows: true }, show: true })
  assert.equal(isPreviewCurrent(state.draft), true)
  state = sessionReducer(state, { type: 'committed', draftId: 'd', pageId: 'p', then: 'summary' })
  assert.equal(state.pages[0].removeShadows, true)
  state = sessionReducer(state, { type: 'edit-page', pageId: 'p', draftId: 'edit' })
  assert.equal(state.draft.removeShadows, true)
  assert.equal(isPreviewCurrent(state.draft), true)
  state = sessionReducer(state, { type: 'set-filter', filter: 'grayscale' })
  assert.equal(isPreviewCurrent(state.draft), false)
  assert.equal(state.draft.removeShadows, true)
})


test('the review swipes to another accepted page only while the open one has no unsaved changes', () => {
  const review = load('example/lib/documentReview.ts')
  const { sessionReducer, canSwitchPage, INITIAL_SESSION, FULL_FRAME } = load('example/lib/scanSession.ts', { './documentReview': review })
  const page = (id) => ({ id, originalUri: `file:///${id}.jpg`, analysis: null, quad: FULL_FRAME, crop: false,
    filter: 'none', removeShadows: true, outputUri: `file:///${id}-out.jpg`, revision: 0 })
  let state = sessionReducer({ ...INITIAL_SESSION, pages: [page('a'), page('b')] }, { type: 'edit-page', pageId: 'a', draftId: 'd1' })
  assert.equal(canSwitchPage(state), true)
  state = sessionReducer(state, { type: 'switch-page', pageId: 'b', draftId: 'd2' })
  assert.equal(state.draft.pageId, 'b')
  assert.equal(state.draft.preview.uri, 'file:///b-out.jpg')
  assert.equal(state.selectedId, 'b')
  // An unsaved filter change keeps the page open.
  state = sessionReducer(state, { type: 'set-filter', filter: 'grayscale' })
  assert.equal(canSwitchPage(state), false)
  assert.equal(sessionReducer(state, { type: 'switch-page', pageId: 'a', draftId: 'd3' }), state)
  // So does a new shot, which is not an accepted page yet.
  const shot = sessionReducer({ ...INITIAL_SESSION, pages: [page('a')] }, { type: 'captured', draftId: 's', uri: 'file:///s.jpg' })
  assert.equal(canSwitchPage(shot), false)
})


test('renders of the current outline are reused for their filter and dropped on a crop change', () => {
  const review = load('example/lib/documentReview.ts')
  const { sessionReducer, isPreviewCurrent, referencedUris, INITIAL_SESSION, FULL_FRAME } = load('example/lib/scanSession.ts', { './documentReview': review })
  const detected = { topLeft: { x: 0.1, y: 0.1 }, topRight: { x: 0.9, y: 0.1 }, bottomRight: { x: 0.9, y: 0.9 }, bottomLeft: { x: 0.1, y: 0.9 } }
  const page = { id: 'p', originalUri: 'file:///p.jpg', analysis: { candidate: { quad: detected } }, quad: FULL_FRAME, crop: false,
    filter: 'none', removeShadows: true, outputUri: 'file:///p-none.jpg', revision: 0 }
  let state = sessionReducer({ ...INITIAL_SESSION, pages: [page] }, { type: 'edit-page', pageId: 'p', draftId: 'd' })
  state = sessionReducer(state, { type: 'set-filter', filter: 'grayscale' })
  assert.equal(isPreviewCurrent(state.draft), false)
  const gray = { uri: 'file:///p-gray.jpg', quad: state.draft.quad, filter: 'grayscale', removeShadows: true }
  state = sessionReducer(state, { type: 'previewed', draftId: 'd', preview: gray, show: true })
  // Back to the saved filter and again to gray: both are shown from the cache, without a render.
  state = sessionReducer(state, { type: 'set-filter', filter: 'none' })
  assert.equal(isPreviewCurrent(state.draft), true)
  assert.equal(state.draft.preview.uri, 'file:///p-none.jpg')
  state = sessionReducer(state, { type: 'set-filter', filter: 'grayscale' })
  assert.equal(state.draft.preview.uri, 'file:///p-gray.jpg')
  // A render the user moved past is kept without being shown.
  const bw = { uri: 'file:///p-bw.png', quad: state.draft.quad, filter: 'blackWhite', removeShadows: true }
  state = sessionReducer(state, { type: 'previewed', draftId: 'd', preview: bw, show: false })
  assert.equal(state.draft.preview.uri, 'file:///p-gray.jpg')
  assert.equal(referencedUris(state).has('file:///p-bw.png'), true)
  // A crop change empties the cache; the shown render stays referenced until its replacement arrives.
  state = sessionReducer(state, { type: 'toggle-crop' })
  assert.deepEqual(state.draft.renders, [])
  assert.equal(referencedUris(state).has('file:///p-bw.png'), false)
  assert.equal(referencedUris(state).has('file:///p-gray.jpg'), true)
  state = sessionReducer(state, { type: 'set-filter', filter: 'none' })
  assert.equal(isPreviewCurrent(state.draft), false)
})


test('a capture keeps its latest frame until it leaves review, and pages keep their thumbnails', () => {
  const review = load('example/lib/documentReview.ts')
  const { sessionReducer, referencedUris, INITIAL_SESSION, FULL_FRAME } = load('example/lib/scanSession.ts', { './documentReview': review })
  let state = sessionReducer(INITIAL_SESSION, { type: 'captured', draftId: 'd', uri: 'file:///photo.jpg', frameUri: 'file:///frame.jpg' })
  assert.equal(state.draft.frameUri, 'file:///frame.jpg')
  assert.equal(referencedUris(state).has('file:///frame.jpg'), true)
  state = sessionReducer(state, { type: 'analysis-failed', draftId: 'd' })
  const preview = { uri: 'file:///page.jpg', thumbnailUri: 'file:///thumb.jpg', quad: FULL_FRAME, filter: 'none', removeShadows: true }
  state = sessionReducer(state, { type: 'previewed', draftId: 'd', preview, show: true })
  state = sessionReducer(state, { type: 'committed', draftId: 'd', pageId: 'p', then: 'summary' })
  assert.equal(state.pages[0].thumbnailUri, 'file:///thumb.jpg')
  const uris = referencedUris(state)
  assert.equal(uris.has('file:///frame.jpg'), false)
  assert.equal(uris.has('file:///thumb.jpg'), true)
  state = sessionReducer(state, { type: 'edit-page', pageId: 'p', draftId: 'e' })
  assert.equal(state.draft.frameUri, null)
  assert.equal(state.draft.preview.thumbnailUri, 'file:///thumb.jpg')
})


test('edits and acceptance during analysis are ignored, not queued for later', () => {
  const review = load('example/lib/documentReview.ts')
  const { sessionReducer, INITIAL_SESSION, FULL_FRAME } = load('example/lib/scanSession.ts', { './documentReview': review })
  const preview = { uri: 'file:///live.jpg', quad: FULL_FRAME, filter: 'none', removeShadows: true }
  const draft = { id: 'd', pageId: null, originalUri: 'file:///photo.jpg', analyzing: true, analysis: null,
    quad: FULL_FRAME, crop: false, filter: 'none', removeShadows: true, cropQuad: null, renders: [], preview }
  let state = { ...INITIAL_SESSION, draft }
  for (const action of [
    { type: 'set-filter', filter: 'grayscale' }, { type: 'set-remove-shadows', value: false }, { type: 'start-crop' },
    { type: 'committed', draftId: 'd', pageId: 'p', then: 'summary' },
  ]) assert.equal(sessionReducer(state, action), state, action.type)
  state = sessionReducer(state, { type: 'analysis-failed', draftId: 'd' })
  assert.equal(state.draft.filter, 'none')
  assert.equal(state.draft.removeShadows, true)
  assert.equal(state.draft.cropQuad, null)
  assert.equal(state.pages.length, 0)
})

test('review waits for the decoded photo and prevents editing or committing a provisional analysis', () => {
  const review = load('example/lib/documentReview.ts')
  const model = load('example/lib/scanSession.ts', { './documentReview': review })
  const jsx = (type, props) => ({ type, props })
  const { ReviewPanel } = load('example/components/ReviewPanel.tsx', {
    '../lib/errorMessage': { errorMessage: () => 'error' },
    'react/jsx-runtime': { jsx, jsxs: jsx },
    'react': { useState: (initial) => [initial, () => {}], useCallback: (callback) => callback },
    'react-native': { Alert: { alert() {} }, Image: 'Image', Modal: 'Modal', Pressable: 'Pressable', ScrollView: 'ScrollView', StyleSheet: { absoluteFill: {} }, Text: 'Text', View: 'View' },
    'react-native-document-scanner': { CropEditor: 'CropEditor', ImageViewer: 'ImageViewer', ImagePager: 'ImagePager' },
    'react-native-safe-area-context': { SafeAreaView: 'SafeAreaView' },
    '../lib/documentReview': review, '../lib/scanSession': model,
    '../styles': { styles: {} }, '../hooks/usePhotoActions': { usePhotoActions: () => ({}) },
    './SheetModal': { SheetModal: 'SheetModal' },
    './ExportSheet': { ExportSheet: 'ExportSheet' }, './PageStrip': { PageStrip: 'PageStrip' },
    './TuningSwitch': { TuningSwitch: 'TuningSwitch' }, './PrimaryButton': { PrimaryButton: 'PrimaryButton' },
  })
  const quad = model.FULL_FRAME
  const draft = { id: 'd', pageId: null, analyzing: true, originalUri: 'file:///photo.jpg',
    quad, cropQuad: null, renders: [], filter: 'none', removeShadows: false,
    preview: { uri: 'file:///photo.jpg', quad, filter: 'none', removeShadows: false } }
  let ready = 0
  const session = { rendering: false, pages: [] }
  const render = (awaitingImage) => {
    const overlay = ReviewPanel({ visible: true, draft, session, awaitingImage, onPreviewReady: () => ready++ })
    const preview = overlay.props.children[0]
    return preview.type(preview.props)
  }
  const nodes = (tree) => !tree || typeof tree !== 'object' ? [] : [tree, ...[tree.props?.children].flat(Infinity).flatMap(nodes)]
  const pending = render(true)
  assert.equal(pending.props.pointerEvents, 'none')
  nodes(pending).find(n => n.type === 'ImagePager').props.onLoad(draft.originalUri)
  assert.equal(ready, 1)
  const loaded = render(false)
  assert.equal(loaded.props.pointerEvents, 'auto')
  const buttons = nodes(loaded).filter(n => n.type === 'PrimaryButton')
  for (const label of ['Done', 'Add page']) {
    assert.equal(buttons.find(n => n.props.label === label).props.disabled, true, label)
  }
  for (const label of ['Shadow', 'Crop']) {
    assert.equal(nodes(loaded).find(n => n.props?.label === label).props.disabled, true, label)
  }
  assert.equal(nodes(loaded).find(n => n.type === 'PageStrip').props.disabled, true)
  draft.analyzing = false
  assert.equal(nodes(render(false)).find(n => n.props?.label === 'Done').props.disabled, false)
  const hidden = ReviewPanel({ visible: false, draft, session, awaitingImage: false, onPreviewReady() {} })
  assert.equal(hidden.props.pointerEvents, 'none')
  assert.equal(hidden.props.importantForAccessibility, 'no-hide-descendants')
  const retainedPreview = hidden.props.children[0]
  assert.equal(retainedPreview.props.visible, false)
  const hiddenContent = retainedPreview.type(retainedPreview.props)
  assert.equal(hiddenContent.props.pointerEvents, 'none')
  assert.equal(nodes(hiddenContent).find(n => n.type === 'ImagePager').props.swipeEnabled, false)
  assert.equal(nodes(hiddenContent).find(n => n.type === 'ImagePager').props.uris[0], draft.originalUri)
  draft.cropQuad = quad
  const cropping = ReviewPanel({ visible: true, draft, session, awaitingImage: false, onPreviewReady() {} })
  assert.equal(cropping.props.children[0].type, retainedPreview.type)
  assert.equal(cropping.props.children[0].props.visible, false)
  assert.ok(cropping.props.children[1], 'crop is a separate screen above the retained pager')
  const automatic = { topLeft: { x: 0.1, y: 0.1 }, topRight: { x: 0.9, y: 0.1 },
    bottomRight: { x: 0.9, y: 0.9 }, bottomLeft: { x: 0.1, y: 0.9 } }
  draft.analysis = { candidate: { quad: automatic } }
  let edited
  let submitted
  session.editQuad = (quad) => { edited = quad }
  session.endCrop = (submit) => { submitted = submit }
  const cropScreen = cropping.props.children[1].type(cropping.props.children[1].props)
  nodes(cropScreen).find(n => n.props?.label === 'No crop').props.onPress()
  assert.deepEqual(edited, model.FULL_FRAME)
  nodes(cropScreen).find(n => n.props?.label === 'Auto crop').props.onPress()
  assert.deepEqual(edited, automatic)
  nodes(cropScreen).find(n => n.props?.label === 'Cancel').props.onPress()
  assert.equal(submitted, false)
  nodes(cropScreen).find(n => n.props?.label === 'Submit').props.onPress()
  assert.equal(submitted, true)
  assert.equal(nodes(loaded).some(n => n.props?.label === 'No crop' || n.props?.label === 'Auto crop'), false)
  assert.equal(nodes(loaded).some(n => n.type === 'ScrollView'), false)

})


test('New scan confirms unaccepted photos and counts an edited saved page only once', () => {
  let session
  let prompt
  let resets = 0
  const jsx = (type, props) => ({ type, props })
  const { default: ScanScreen } = load('example/app/index.tsx', {
    'react': { useEffect() {}, useLayoutEffect() {}, useSyncExternalStore: (subscribe, snapshot) => snapshot(), useRef: () => ({ current: null }), useState: (value) => [value, () => {}] },
    'react/jsx-runtime': { jsx, jsxs: jsx },
    'react-native': { Alert: { alert: (...args) => { prompt = args } }, BackHandler: {}, Linking: {},
      Modal: 'Modal', StyleSheet: { absoluteFill: {} }, Text: 'Text', View: 'View' },
    'react-native-safe-area-context': { SafeAreaView: 'SafeAreaView' },
    'react-native-document-scanner': { DocumentScanner: { getSampleFolder: () => ({ collecting: false }) }, Camera: 'Camera', useCameraPermission: () => ({ status: 'granted' }) },
    '../components/OptionsPanel': { DEFAULT_TUNING: {}, OptionsPanel: 'OptionsPanel' },
    '../components/PrimaryButton': { PrimaryButton: 'PrimaryButton' },
    '../components/ReviewPanel': { ReviewPanel: 'ReviewPanel' },
    '../components/SheetModal': { SheetModal: 'SheetModal' },
    '../components/ShutterButton': { ShutterButton: 'ShutterButton' },
    '../components/TuningSwitch': { TuningSwitch: 'TuningSwitch' },
    '../hooks/useScanSession': { useScanSession: () => session },
    '../lib/errorMessage': {}, '../lib/scanSession': { MAX_PAGES: 50 }, '../styles': { styles: {} },
  })
  const nodes = (tree) => !tree || typeof tree !== 'object' ? [] :
    [tree, ...[tree.props?.children].flat(Infinity).flatMap(nodes)]
  for (const [pages, draft, count] of [
    [[], { id: 'new', pageId: null }, 1],
    [[{ id: 'saved' }], { id: 'new', pageId: null }, 2],
    [[{ id: 'saved' }], { id: 'edit', pageId: 'saved' }, 1],
  ]) {
    session = { pages, draft, reset: () => resets++ }
    prompt = undefined
    const before = resets
    nodes(ScanScreen()).find((node) => node.type === 'ReviewPanel').props.onNewScan()
    assert.equal(resets, before, 'nothing is discarded before confirmation')
    assert.equal(prompt[0], `Discard ${count} unsaved page${count === 1 ? '' : 's'}?`)
    assert.equal(prompt[2].find((button) => button.text === 'Keep').style, 'cancel')
    prompt[2].find((button) => button.text === 'Discard').onPress()
    assert.equal(resets, before + 1)
  }
})

test('the global shadow default affects new captures without changing saved pages and survives a new scan', () => {
  const review = load('example/lib/documentReview.ts')
  const { sessionReducer, INITIAL_SESSION, FULL_FRAME } = load('example/lib/scanSession.ts', { './documentReview': review })
  const page = { id: 'p', originalUri: 'file:///p.jpg', analysis: null, quad: FULL_FRAME, crop: false,
    filter: 'none', removeShadows: true, outputUri: 'file:///p-out.jpg', revision: 0 }
  let state = sessionReducer({ ...INITIAL_SESSION, pages: [page], selectedId: 'p' },
    { type: 'set-default-remove-shadows', value: false })
  assert.equal(state.pages[0], page)
  state = sessionReducer(state, { type: 'captured', draftId: 'd', uri: 'file:///new.jpg' })
  assert.equal(state.draft.removeShadows, false)
  state = sessionReducer(state, { type: 'set-default-remove-shadows', value: true })
  assert.equal(state.draft.removeShadows, false, 'changing the default does not change the open page')
  state = sessionReducer(state, { type: 'reset' })
  assert.equal(state.removeShadows, true)
  state = sessionReducer(state, { type: 'set-default-remove-shadows', value: false })
  state = sessionReducer(state, { type: 'reset' })
  assert.equal(state.removeShadows, false)
  assert.deepEqual(state.pages, [])
})

test('saving stays in the selected pager page, Add page returns to camera, and deleting chooses the adjacent page', () => {
  const review = load('example/lib/documentReview.ts')
  const { sessionReducer, INITIAL_SESSION, FULL_FRAME } = load('example/lib/scanSession.ts', { './documentReview': review })
  const page = (id) => ({ id, originalUri: `file:///${id}.jpg`, analysis: null, quad: FULL_FRAME, crop: false,
    filter: 'none', removeShadows: true, outputUri: `file:///${id}-out.jpg`, thumbnailUri: `file:///${id}-thumb.jpg`, revision: 0 })
  let state = { ...INITIAL_SESSION, pages: [page('a'), page('b')], selectedId: 'a', summary: true }
  state = sessionReducer(state, { type: 'edit-page', pageId: 'a', draftId: 'e1' })
  state = sessionReducer(state, { type: 'committed', draftId: 'e1', pageId: 'a', then: 'summary' })
  assert.equal(state.draft, null)
  assert.equal(state.summary, true)
  assert.equal(state.selectedId, 'a')
  state = sessionReducer(state, { type: 'edit-page', pageId: 'a', draftId: 'e2' })
  state = sessionReducer(state, { type: 'committed', draftId: 'e2', pageId: 'a', then: 'camera' })
  assert.equal(state.summary, false)
  state = sessionReducer(state, { type: 'show-summary', open: true })
  state = sessionReducer(state, { type: 'delete', pageId: 'a' })
  assert.equal(state.selectedId, 'b')
  assert.equal(state.summary, true)
  state = sessionReducer(state, { type: 'captured', draftId: 'new', uri: 'file:///new.jpg' })
  state = sessionReducer(state, { type: 'discard-draft' })
  assert.equal(state.summary, true)
  assert.equal(state.selectedId, 'b')
  state = sessionReducer(state, { type: 'delete', pageId: 'b' })
  assert.equal(state.summary, false)
  assert.equal(state.selectedId, null)
})


test('Crop toggles preserve a manual outline even without a detected page, including after save and reopen', () => {
  const review = load('example/lib/documentReview.ts')
  const { sessionReducer, INITIAL_SESSION, FULL_FRAME } = load('example/lib/scanSession.ts', { './documentReview': review })
  const manual = { topLeft: { x: 0.1, y: 0.1 }, topRight: { x: 0.9, y: 0.1 },
    bottomRight: { x: 0.9, y: 0.9 }, bottomLeft: { x: 0.1, y: 0.9 } }
  let state = sessionReducer(INITIAL_SESSION, { type: 'captured', draftId: 'd', uri: 'file:///photo.jpg' })
  state = sessionReducer(state, { type: 'analysis-failed', draftId: 'd' })
  state = sessionReducer(state, { type: 'start-crop' })
  state = sessionReducer(state, { type: 'edited', quad: manual })
  state = sessionReducer(state, { type: 'end-crop', submit: true })
  assert.equal(state.draft.crop, true)
  assert.deepEqual(state.draft.quad, manual)
  state = sessionReducer(state, { type: 'toggle-crop' })
  assert.equal(state.draft.crop, false)
  assert.equal(state.draft.quad, FULL_FRAME)
  state = sessionReducer(state, { type: 'previewed', draftId: 'd',
    preview: { uri: 'file:///full.jpg', quad: FULL_FRAME, filter: 'none', removeShadows: true }, show: true })
  state = sessionReducer(state, { type: 'committed', draftId: 'd', pageId: 'p', then: 'summary' })
  state = sessionReducer(state, { type: 'edit-page', pageId: 'p', draftId: 'e' })
  state = sessionReducer(state, { type: 'toggle-crop' })
  assert.equal(state.draft.crop, true)
  assert.deepEqual(state.draft.quad, manual)
  state = sessionReducer(state, { type: 'start-crop' })
  state = sessionReducer(state, { type: 'edited', quad: FULL_FRAME })
  state = sessionReducer(state, { type: 'end-crop', submit: false })
  assert.deepEqual(state.draft.quad, manual)
  assert.equal(state.draft.crop, true)
})


test('camera forwards model preparation and failures to native without React loading state', () => {
  const received = []
  const { Camera } = load('src/DocumentScannerCamera.tsx', {
    'react': {
      forwardRef: (render) => render,
      useLayoutEffect: () => {},
      useMemo: (create) => create(),
      useRef: (value) => ({ current: value }),
      useImperativeHandle: () => {},
    },
    'react/jsx-runtime': { jsx: (type, props) => ({ type, props }) },
    'react-native': { processColor: () => 0 },
    'react-native-nitro-modules': { callback: (fn) => fn, getHostComponent: () => 'NativeCamera' },
    '../nitrogen/generated/shared/json/DocumentScannerCameraViewConfig.json': {},
    './ScannerError': errors,
  })
  const view = Camera({ quadTensorModel: ' /models/page.tflite ', onError: (error) => received.push(error) }, null)
  assert.equal(view.props.quadTensorModel, ' /models/page.tflite ')
  assert.equal(view.props.mode, 'scanner')
  const failure = { code: 'model/load-failed' }
  view.props.onError(failure)
  assert.equal(received.length, 1)
  assert.equal(received[0] instanceof errors.ScannerError, true)
  assert.equal(received[0].code, failure.code)
  assert.equal(Camera({}, null).props.quadTensorModel, '')
  assert.equal(Camera({ quadTensorModel: '/models/page.tflite', mode: 'off' }, null).props.mode, 'off')
})
