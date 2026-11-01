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
const sessionAdapter = load('src/ScanSession.ts', { './ScannerError': errors })

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

test('utility facade keeps typed sample controls separate from explicit session lifetimes', async () => {
  let folder = { collecting: false }
  const calls = []
  const native = {
    pickSampleFolderResult: async () => ({ code: 'permission/request-failed' }),
    getSampleFolder: () => folder,
    setSampleFolder: (uri) => { calls.push(['folder', uri]); folder = { uri, collecting: false } },
    setSampleCollection: (on) => { calls.push(['collect', on]); folder = { ...folder, collecting: on } },
    clearSampleFolder: () => { folder = { collecting: false } },
  }
  const { DocumentScanner } = load('src/index.ts', {
    'react-native-nitro-modules': { NitroModules: { createHybridObject: () => native } },
    './ScannerError': errors, './ScanSession': sessionAdapter,
    './DocumentScannerCamera': {}, './DocumentCropEditor': {}, './ImageViewer': {}, './ImagePager': {}, './PageThumbnail': {}, './CameraPermission': {},
  })
  DocumentScanner.setSampleFolder('file:///samples')
  DocumentScanner.setSampleCollection(true)
  assert.deepEqual(DocumentScanner.getSampleFolder(), { uri: 'file:///samples', collecting: true })
  await assert.rejects(DocumentScanner.pickSampleFolder(), (error) => error instanceof errors.ScannerError && error.code === 'permission/request-failed')
  DocumentScanner.clearSampleFolder()
  assert.deepEqual(DocumentScanner.getSampleFolder(), { collecting: false })
  assert.deepEqual(calls, [['folder', 'file:///samples'], ['collect', true]])
  assert.deepEqual(Object.keys(DocumentScanner).sort(), [
    'clearSampleFolder', 'createSession', 'getSampleFolder', 'pickSampleFolder', 'setSampleCollection', 'setSampleFolder',
  ].sort())
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
    constructor(parent, name) { this.name = name; this.uri = `file:///cache/exports/${name}` }
    create() { if (this.name !== 'document-scanner-exports') files.set(this.name, this) }
    get exists() { return this.name === 'document-scanner-exports' || files.has(this.name) }
    list() { return [...files.values()] }
    delete() { files.delete(this.name) }
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


test('image exports use unique host directories and share seven-day retention without touching sessions', () => {
  const f = pdfFixture(); const exports = f.load(); const first = exports.newImageExport()
  const second = exports.newImageExport(); const third = f.load().newImageExport()
  assert.equal(new Set([first.uri, second.uri, third.uri]).size, 3)
  f.add('session-active'); f.add('unrelated.jpg')
  exports.removeOldPdfExports()
  assert.equal(f.files.has(first.name), true)
  f.advance(8 * 24 * 60 * 60 * 1000)
  exports.removeOldPdfExports()
  assert.equal(f.files.has(first.name), false)
  assert.equal(f.files.has(second.name), false)
  assert.equal(f.files.has(third.name), false)
  assert.equal(f.files.has('session-active'), true)
  assert.equal(f.files.has('unrelated.jpg'), true)
})

const appModel = load('example/lib/scanSession.ts')
function appSession() {
  let snapshot = { version: 0, closed: false, pageIds: [], pages: {} }
  const active = new Set()
  const all = []
  const calls = []
  const native = {
    getSession: () => snapshot,
    subscribe(listener) { active.add(listener); all.push(listener); return () => active.delete(listener) },
    close: async () => { calls.push(['close']) },
    setDocumentCrop: async (...args) => { calls.push(['quad', ...args]) },
    setFilter: async (...args) => { calls.push(['filter', ...args]) },
    setShadowRemoval: async (...args) => { calls.push(['shadow', ...args]) },
    removePage: async (id) => {
      calls.push(['remove', id])
      const pages = { ...snapshot.pages }; delete pages[id]
      snapshot = { ...snapshot, pages, pageIds: snapshot.pageIds.filter(p => p !== id) }
      for (const listener of active) listener()
    },
  }
  return { native, calls, active, all,
    publish(pages, version = snapshot.version + 1) {
      snapshot = { version, closed: false, pageIds: [...snapshot.pageIds.filter(id => Object.hasOwn(pages, id)), ...Object.keys(pages).filter(id => !snapshot.pageIds.includes(id))], pages }
      for (const listener of active) listener()
    },
  }
}
function appPage(id) {
  return { id, revision: 1, readyRevision: 1, ready: true, analyzing: false, rendering: false,
    quad: FULL_SESSION_QUAD, options: { filter: 'none', removeShadows: true } }
}
function uiFixture(owner = appSession()) {
  let resets = 0
  const slots = []; let cursor = 0; let effects = []; let dependencies = []
  const { useScanReview } = load('example/hooks/useScanReview.ts', {
    react: {
      useReducer(reducer, initial) { const i = cursor++; slots[i] ??= initial; return [slots[i], action => { slots[i] = reducer(slots[i], action) }] },
      useState(initial) { const i = cursor++; if (!(i in slots)) slots[i] = initial; return [slots[i], value => { slots[i] = value }] },
      useRef(initial) { const i = cursor++; slots[i] ??= { current: initial }; return slots[i] },
      useSyncExternalStore(subscribe, read) { return read() },
      useEffect(effect, deps) { const i = cursor++; if (dependencies[i] !== deps[0]) { dependencies[i] = deps[0]; effects.push(effect) } },
    },
    'react-native': { Alert: { alert() {} } }, '../lib/errorMessage': { errorMessage: String }, '../lib/scanSession': appModel,
  })
  const render = () => { cursor = 0; const ui = useScanReview(owner.native, () => { resets++ }); const pending = effects; effects = []; pending.forEach(fn => fn()); return ui }
  return { owner, render, get resets() { return resets } }
}

test('React scan UI selects captures immediately and reconciles membership without duplicates', () => {
  const f = uiFixture(); f.owner.publish({ first: appPage('first') }); f.render()
  f.owner.publish({ first: appPage('first'), new: appPage('new') })
  f.render().reviewCapture('new')
  assert.deepEqual(f.render().pageIds, ['first', 'new'])
  assert.equal(f.render().selectedId, 'new')
  f.owner.publish({ first: appPage('first'), new: appPage('new') }); f.render()
  assert.deepEqual(f.render().pageIds, ['first', 'new'])
  f.owner.publish({ new: appPage('new') }); f.render()
  assert.deepEqual(f.render().pageIds, ['new'])
})

test('membership changes reconcile selection and review before returning native view props', () => {
  const f = uiFixture()
  f.owner.publish({ a: appPage('a'), b: appPage('b') })
  f.render().reviewCapture('b')
  f.owner.publish({ a: appPage('a') })
  const ui = f.render()
  assert.deepEqual(ui.pageIds, ['a'])
  assert.equal(ui.selectedId, 'a')
  assert.equal(ui.screen.type, 'summary')
  f.owner.publish({})
  assert.equal(f.render().selectedId, null)
})

test('React scan UI keeps crop drafts native and persists saved filter and shadow edits', async () => {
  const f = uiFixture(); f.owner.publish({ p: appPage('p') }); f.render()
  f.render().reviewCapture('p')
  f.render().startCrop(); f.render().cancelCrop()
  assert.deepEqual(f.owner.calls, [])
  let submissions = 0
  f.render().startCrop(); f.render().submitCrop({ submit: async () => { submissions++ } })
  await Promise.resolve()
  assert.equal(submissions, 1)
  assert.deepEqual(f.owner.calls, [])
  await new Promise(setImmediate)
  f.render().setFilter('grayscale'); await Promise.resolve(); await Promise.resolve()
  f.render().setShadowRemoval(false)
  assert.deepEqual(f.owner.calls, [['filter', 'p', 'grayscale'], ['shadow', 'p', false]])
  await Promise.resolve(); await Promise.resolve()
  f.render().navigate('camera')
  assert.equal(f.render().screen.type, 'camera')
})

test('React export UX lock rejects immediate overlap and unlocks after failure', async () => {
  const f = uiFixture(); let finish
  const first = f.render().run(() => new Promise(resolve => { finish = resolve }))
  let overlap = false
  await f.render().run(async () => { overlap = true })
  assert.equal(overlap, false); assert.equal(f.render().working, true)
  f.render().reset(); assert.equal(f.resets, 0)
  finish(); await first
  await assert.rejects(f.render().run(async () => { throw Error('failed') }))
  assert.equal(f.render().working, false)
  f.render().reset(); assert.equal(f.resets, 1)
})

const nodes = (tree) => !tree || typeof tree !== 'object' ? [] :
  [tree, ...[tree.props?.children].flat(Infinity).flatMap(nodes)]
const jsx = (type, props) => ({ type, props })
function reviewComponent() {
  return load('example/components/ReviewPanel.tsx', {
    '../lib/errorMessage': { errorMessage: () => 'error' },
    'react/jsx-runtime': { jsx, jsxs: jsx },
    'react': { useState: (initial) => [initial, () => {}], useRef: () => ({ current: { setPreset: async () => {} } }), useCallback: (callback) => callback },
    'react-native': { Alert: { alert() {} }, Pressable: 'Pressable', StyleSheet: { absoluteFill: {} }, Text: 'Text', View: 'View' },
    'react-native-document-scanner': { CropEditor: 'CropEditor', ImagePager: 'ImagePager' },
    'react-native-safe-area-context': { SafeAreaView: 'SafeAreaView' },
    '../lib/scanSession': appModel,
    '../styles': { styles: {} }, '../hooks/usePhotoActions': { usePhotoActions: () => ({}) },
    './SheetModal': { SheetModal: 'SheetModal' }, './ExportSheet': { ExportSheet: 'ExportSheet' },
    './PageStrip': { PageStrip: 'PageStrip' }, './ActionButton': { ActionButton: 'ActionButton' },
    './PrimaryButton': { PrimaryButton: 'PrimaryButton' },
  }).ReviewPanel
}
test('review retains its ID pager under crop, uses page IDs and native crop presets without preview handoff state', () => {
  const ReviewPanel = reviewComponent()
  const review = { type: 'review', pageId: 'p', origin: 'capture', cropping: false }
  const page = { ...appPage('p'), ready: false, analyzing: true }
  let submitted
  const session = { screen: review, native: {}, page, selectedId: 'p', pageIds: ['p'], select() {},
    cancelCrop: () => { submitted = false }, submitCrop: () => { submitted = true }, run: async fn => fn() }
  const props = { visible: true, session, sampling: true }
  const render = () => {
    const preview = ReviewPanel(props).props.children[0]
    return preview.type(preview.props)
  }
  const immediatelyVisible = render(true)
  assert.equal(immediatelyVisible.props.pointerEvents, 'auto')
  assert.equal(nodes(immediatelyVisible).find(n => n.type === 'ImagePager').props.selectedPageId, 'p')
  const inFlight = nodes(render(false))
  assert.equal(inFlight.find(n => n.props?.label === 'Done').props.disabled, true)
  for (const label of ['Shadow', 'Crop', 'Filter · Original']) {
    assert.equal(inFlight.find(n => n.props?.label === label).props.disabled, false, `${label} accepts pending native work`)
  }
  assert.equal(inFlight.find(n => n.props?.label === 'Sample').props.disabled, true)
  page.analyzing = false
  assert.equal(nodes(render(false)).find(n => n.props?.label === 'Sample').props.disabled, false,
    'sampling waits for analysis, not rendering')
  page.ready = true
  assert.equal(nodes(render(false)).find(n => n.props?.label === 'Done').props.disabled, false)
  const hidden = ReviewPanel({ ...props, visible: false })
  assert.equal(hidden.props.pointerEvents, 'none')
  const retained = hidden.props.children[0]
  assert.equal(nodes(retained.type(retained.props)).find(n => n.type === 'ImagePager').props.pageIds, undefined)
  review.cropping = true
  const automatic = { ...FULL_SESSION_QUAD, topLeft: { x: .1, y: .1 } }
  page.analysis = { candidate: { quad: automatic } }
  const crop = ReviewPanel(props)
  assert.equal(crop.props.children[0].type, retained.type)
  assert.equal(crop.props.children[0].props.visible, false)
  const editor = crop.props.children[1].type(crop.props.children[1].props)
  assert.equal(nodes(editor).find(n => n.type === 'CropEditor').props.pageId, 'p')
  nodes(editor).find(n => n.props?.label === 'No crop').props.onPress()
  assert.equal('quad' in nodes(editor).find(n => n.type === 'CropEditor').props, false)
  nodes(editor).find(n => n.props?.label === 'Auto crop').props.onPress()
  assert.equal('onQuadChange' in nodes(editor).find(n => n.type === 'CropEditor').props, false)
  nodes(editor).find(n => n.props?.label === 'Cancel').props.onPress()
  assert.equal(submitted, false)
  nodes(editor).find(n => n.props?.label === 'Submit').props.onPress()
  assert.equal(submitted, true)
})

test('gallery and share use session ID exports, preserve order, and report permission/storage/share failures', async () => {
  let permission = { granted: true }; let failure; let alert
  const calls = []
  const session = {
    exportImages: async (dir) => { calls.push(['images', dir]); if (failure === 'export') throw Error('storage'); return ['file:///b.png', 'file:///a.jpg'] },
    createPdf: async (options) => { calls.push(['pdf', options]); return 'file:///scan.pdf' },
  }
  const { usePhotoActions } = load('example/hooks/usePhotoActions.ts', {
    'react-native': { Alert: { alert: (...args) => { alert = args } } },
    'expo-media-library': { usePermissions: () => [permission, async () => permission],
      Asset: { create: async (uri) => { calls.push(['gallery', uri]); if (failure === 'gallery') throw Error('gallery') } } },
    'expo-sharing': { shareAsync: async (...args) => { calls.push(['share', ...args]); if (failure === 'share') throw Error('share') } },
    '../lib/errorMessage': { errorMessage: (error) => error.message },
    '../lib/pdfExports': { newImageExport: () => ({ uri: 'file:///exports/images-unique' }), newPdfExport: () => ({ uri: 'file:///exports/scan-unique.pdf' }) },
  })
  const f = uiFixture(); f.store = { run: f.render().run, getSession: f.render, stop() {} }
  let actions = usePhotoActions(session, f.store.run)
  await actions.saveAll()
  assert.deepEqual(calls.slice(0, 3), [['images', 'file:///exports/images-unique'], ['gallery', 'file:///b.png'], ['gallery', 'file:///a.jpg']])
  assert.equal(f.store.getSession().working, false)
  await actions.sharePdf({ imageFit: 'contain' })
  assert.deepEqual(calls[3], ['pdf', { imageFit: 'contain', outputPath: 'file:///exports/scan-unique.pdf' }])
  assert.equal(calls[4][0], 'share')
  failure = 'share'; await actions.sharePdf({})
  assert.equal(alert[0], 'Could not export the PDF.')
  for (failure of ['export', 'gallery']) {
    await actions.saveAll(); assert.equal(alert[0], 'Could not save the pages.')
    assert.equal(f.store.getSession().working, false)
  }
  permission = { granted: false }; actions = usePhotoActions(session, f.store.run)
  const before = calls.length
  await actions.saveAll()
  assert.equal(alert[0], 'Photo library access needed')
  assert.equal(calls.length, before)
  f.store.stop()
})

test('scanner creates only after commit, replaces sessions on New scan and closes on unmount/replay', () => {
  const slots = []; const refs = []; const effects = []; const owners = []; let cursor = 0; let refCursor = 0
  const Scanner = load('example/app/index.tsx', {
    react: {
      useState(initial) { const i = cursor++; if (!(i in slots)) slots[i] = initial
        return [slots[i], value => { slots[i] = typeof value === 'function' ? value(slots[i]) : value }] },
      useRef(initial) { const i = refCursor++; refs[i] ??= { current: initial }; return refs[i] },
      useEffect(effect) { effects.push(effect) },
    },
    'react/jsx-runtime': { jsx, jsxs: jsx }, 'react-native': { View: 'View' },
    'react-native-safe-area-context': {},
    'react-native-document-scanner': { DocumentScanner: { createSession() {
      const owner = appSession(); owners.push(owner); return owner.native
    } } },
    '../components/OptionsPanel': { DEFAULT_TUNING: {} }, '../components/PrimaryButton': {},
    '../components/ReviewPanel': {}, '../components/SheetModal': {}, '../components/ShutterButton': {},
    '../components/ActionButton': {}, '../hooks/useScanReview': {}, '../lib/errorMessage': {},
    '../lib/labels': load('example/lib/labels.ts'),
    '../lib/pdfExports': { removeOldPdfExports() {} }, '../lib/scanSession': appModel, '../styles': { styles: {} },
  }).default
  const render = () => { cursor = 0; refCursor = 0; effects.length = 0; return Scanner() }
  assert.equal(render().type, 'View'); assert.equal(owners.length, 0)
  const cleanup = effects[0]()
  let view = render(); assert.equal(view.props.native, owners[0].native)
  view.props.setRemoveShadows(false); view.props.onReset()
  assert.deepEqual(owners[0].calls, [['close']])
  view = render(); assert.equal(view.props.native, owners[1].native)
  assert.equal(view.props.removeShadows, false)
  cleanup(); assert.deepEqual(owners[1].calls, [['close']])
  const replayCleanup = effects[0]()
  assert.equal(render().props.native, owners[2].native)
  replayCleanup(); assert.deepEqual(owners[2].calls, [['close']])
})

test('camera forwards model preparation and failures to native without React loading state', () => {
  const received = []
  const fixture = sessionFixture()
  const session = fixture.create()
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
    './ScanSession': sessionAdapter,
  })
  const view = Camera({ session, quadTensorModel: ' /models/page.tflite ', onError: (error) => received.push(error) }, null)
  assert.equal(view.props.quadTensorModel, ' /models/page.tflite ')
  assert.equal(view.props.mode, 'scanner')
  assert.equal(view.props.session, fixture.native)
  const failure = { code: 'model/load-failed' }
  view.props.onError(failure)
  assert.equal(received.length, 1)
  assert.equal(received[0] instanceof errors.ScannerError, true)
  assert.equal(received[0].code, failure.code)
  assert.equal(Camera({ session }, null).props.quadTensorModel, '')
  assert.equal(Camera({ session, quadTensorModel: '/models/page.tflite', mode: 'off' }, null).props.mode, 'off')
})


const FULL_SESSION_QUAD = {
  topLeft: { x: 0, y: 0 }, topRight: { x: 1, y: 0 },
  bottomRight: { x: 1, y: 1 }, bottomLeft: { x: 0, y: 1 },
}
function sessionPage(id, changes = {}) {
  return { id, revision: 1, readyRevision: 1, analyzing: false, rendering: false, ready: true,
    quad: FULL_SESSION_QUAD, options: { filter: 'none', removeShadows: true }, ...changes }
}
function sourceSnapshot(sourceVersion, pages = {}, closed = false, pageIds = Object.keys(pages)) { return { sourceVersion, closed, pageIds, pages } }

function sessionFixture(initial = sourceSnapshot(0)) {
  let previous = structuredClone(initial)
  function change(next) {
    const result = { sourceVersion: next.sourceVersion, closed: next.closed,
      updatedPages: Object.fromEntries(Object.entries(next.pages).filter(([id, page]) => JSON.stringify(previous.pages[id]) !== JSON.stringify(page))),
      removedPageIds: Object.keys(previous.pages).filter(id => !Object.hasOwn(next.pages, id)) }
    if (JSON.stringify(previous.pageIds) !== JSON.stringify(next.pageIds)) result.pageIds = next.pageIds
    previous = structuredClone(next)
    return result
  }
  let current = structuredClone(initial)
  let callback
  let finish
  let reads = 0
  let removals = 0
  let closeCalls = 0
  let observeHook = () => {}
  let readHook
  let closeEvent = true
  const calls = []
  let nextFrame = 0
  const pendingFrames = new Map()
  const frames = {
    request(fn) { const id = ++nextFrame; pendingFrames.set(id, fn); return id },
    cancel(id) { pendingFrames.delete(id) },
    tick() {
      const pending = [...pendingFrames.entries()]
      pendingFrames.clear()
      for (const [, fn] of pending) fn()
    },
    get size() { return pendingFrames.size },
  }
  const command = async (kind, id, value) => {
    calls.push([kind, id, value])
    if (current.closed) return { code: 'processor/disposed' }
    return Object.hasOwn(current.pages, id) ? true : { code: 'input/invalid' }
  }
  const native = {
    observeResult(fn) {
      callback = fn
      const snapshot = structuredClone(current)
      observeHook()
      let active = true
      return { snapshot, unsubscribe() { if (active) { active = false; removals++ } } }
    },
    getSession() { reads++; return readHook ? readHook() : structuredClone(current) },
    setDocumentCropResult: (id, quad) => command('quad', id, quad),
    setFilterResult: (id, filter) => command('filter', id, filter),
    setShadowRemovalResult: (id, shadows) => command('shadows', id, shadows),
    removePageResult: (id) => command('remove', id),
    closeResult() {
      closeCalls++
      current = sourceSnapshot(current.sourceVersion + 1, {}, true)
      if (closeEvent) callback?.(change(current))
      return new Promise((resolve) => { finish = resolve })
    },
  }
  return {
    native, frames, calls,
    create: () => sessionAdapter.createScanSession(native, frames),
    emit(next) { current = structuredClone(next); callback?.(change(current)) },
    send(next) { callback?.(change(next)) },
    finishClose(result = true) { assert.equal(typeof finish, 'function'); finish(result) },
    onObserve(fn) { observeHook = fn },
    onRead(fn) { readHook = fn },
    suppressCloseEvent() { closeEvent = false },
    get current() { return current },
    get reads() { return reads },
    get removals() { return removals },
    get closeCalls() { return closeCalls },
  }
}

test('awaited page mutations publish accepted state before resolving without waiting for a frame', async () => {
  const f = sessionFixture(sourceSnapshot(0, { p: sessionPage('p') }))
  const session = f.create()
  const native = sessionAdapter.getNativeScanSession(session)
  let notifications = 0
  session.subscribe(() => { notifications++ })
  native.setFilterResult = async () => {
    f.emit(sourceSnapshot(1, { p: sessionPage('p', { options: { filter: 'grayscale' }, revision: 2, ready: false, rendering: true }) }))
    return true
  }
  await session.setFilter('p', 'grayscale')
  assert.equal(session.getSession().pages.p.options.filter, 'grayscale')
  assert.equal(session.getSession().pages.p.rendering, true)
  assert.equal(notifications, 1)
  assert.equal(f.frames.size, 0)
  native.removePageResult = async () => { f.emit(sourceSnapshot(2)); return true }
  await session.removePage('p')
  assert.deepEqual(session.getSession().pageIds, [])
  assert.equal(notifications, 2)
})

test('session snapshots are stable and pending frames never leak into reads or subscription', () => {
  const fixture = sessionFixture(sourceSnapshot(30, { a: sessionPage('a') }))
  const session = fixture.create()
  const initial = session.getSession()
  assert.equal(initial.version, 0)
  assert.equal('sourceVersion' in initial, false)
  assert.equal(session.getSession(), initial)
  let notifications = 0
  session.subscribe(() => { notifications++; assert.equal(session.getSession().version, 1) })
  fixture.emit(sourceSnapshot(31, { a: sessionPage('a', { revision: 2 }) }))
  assert.equal(fixture.frames.size, 1)
  assert.equal(session.getSession(), initial)
  const remove = session.subscribe(() => { notifications++ })
  assert.equal(session.getSession(), initial)
  assert.equal(fixture.reads, 0)
  assert.equal(notifications, 0)
  fixture.frames.tick()
  assert.equal(notifications, 2)
  assert.equal(session.getSession().version, 1)
  assert.equal(session.getSession().pages.a.revision, 2)
  assert.equal(session.getSession(), session.getSession())
  remove()
  remove()
})

test('session batches bursts, shares unchanged pages and freezes owned copies recursively', () => {
  const analysis = { sourceWidth: 640, sourceHeight: 480, duration: 3,
    candidate: { quad: FULL_SESSION_QUAD, score: .9 } }
  const fixture = sessionFixture(sourceSnapshot(0, { a: sessionPage('a', { analysis }), b: sessionPage('b') }))
  const session = fixture.create()
  const before = session.getSession()
  let calls = 0
  session.subscribe(() => { calls++ })
  fixture.emit(sourceSnapshot(1, { a: sessionPage('a', { analysis, revision: 2 }), b: sessionPage('b', { rendering: true }) }))
  fixture.emit(sourceSnapshot(2, { a: sessionPage('a', { analysis, revision: 3 }), b: sessionPage('b') }))
  fixture.emit(sourceSnapshot(3, { a: sessionPage('a', { analysis, revision: 4 }), b: sessionPage('b') }))
  assert.equal(fixture.frames.size, 1)
  fixture.frames.tick()
  const after = session.getSession()
  assert.equal(calls, 1)
  assert.equal(after.version, 1)
  assert.notEqual(after.pages.a, before.pages.a)
  assert.equal(after.pages.b, before.pages.b)
  assert.equal(after.pages.a.revision, 4)
  for (const value of [after, after.pages, after.pages.a, after.pages.a.analysis,
    after.pages.a.analysis.candidate.quad.topLeft, after.pages.a.options]) assert.equal(Object.isFrozen(value), true)
  fixture.current.pages.a.analysis.sourceWidth = 999
  assert.equal(after.pages.a.analysis.sourceWidth, 640)
  assert.equal(before.pages.a.revision, 1)
})

test('native-only versions and reverted metadata preserve public identity without notifying', () => {
  const fixture = sessionFixture(sourceSnapshot(50, { a: sessionPage('a') }))
  const session = fixture.create()
  const before = session.getSession()
  let calls = 0
  session.subscribe(() => { calls++ })
  fixture.emit(sourceSnapshot(51, { a: sessionPage('a', { analysis: undefined }) }))
  fixture.frames.tick()
  assert.equal(session.getSession(), before)
  fixture.emit(sourceSnapshot(52, { a: sessionPage('a', { rendering: true }) }))
  fixture.emit(sourceSnapshot(53, { a: sessionPage('a') }))
  fixture.frames.tick()
  assert.equal(session.getSession(), before)
  assert.equal(calls, 0)
  fixture.send(sourceSnapshot(52, {}))
  fixture.send(sourceSnapshot(53, {}))
  assert.equal(fixture.frames.size, 0)
})

test('session initialization buffers callbacks until its atomic snapshot is installed', () => {
  const fixture = sessionFixture(sourceSnapshot(5, { a: sessionPage('a') }))
  fixture.onObserve(() => {
    fixture.emit(sourceSnapshot(6, { a: sessionPage('a', { revision: 2 }) }))
    fixture.emit(sourceSnapshot(7, { a: sessionPage('a', { revision: 3 }) }))
  })
  const session = fixture.create()
  assert.equal(session.getSession().version, 0)
  assert.equal(session.getSession().pages.a.revision, 1)
  assert.equal(fixture.frames.size, 1)
  let calls = 0
  session.subscribe(() => { calls++ })
  fixture.frames.tick()
  assert.equal(session.getSession().pages.a.revision, 3)
  assert.equal(session.getSession().version, 1)
  assert.equal(calls, 1)
})

test('version gaps reconcile a full native snapshot and serialize callbacks during reconciliation', () => {
  const fixture = sessionFixture(sourceSnapshot(1, { a: sessionPage('a') }))
  const session = fixture.create()
  fixture.onRead(() => {
    fixture.emit(sourceSnapshot(8, { a: sessionPage('a', { revision: 8 }) }))
    return sourceSnapshot(7, { a: sessionPage('a', { revision: 7 }) })
  })
  fixture.send(sourceSnapshot(5, { a: sessionPage('a', { revision: 5 }) }))
  assert.equal(fixture.reads, 1)
  assert.equal(session.getSession().pages.a.revision, 1)
  fixture.frames.tick()
  assert.equal(session.getSession().version, 1)
  assert.equal(session.getSession().pages.a.revision, 8)
  fixture.send(sourceSnapshot(7, {}))
  assert.equal(fixture.frames.size, 0)
})

test('full reconciliation can remove pages and handles opaque IDs without object prototypes', () => {
  const pages = Object.fromEntries(['__proto__', 'constructor', 'a'].map((id) => [id, sessionPage(id)]))
  const fixture = sessionFixture(sourceSnapshot(0, pages))
  const session = fixture.create()
  const before = session.getSession()
  assert.equal(Object.keys(before.pages).length, 3)
  const remaining = Object.fromEntries([['__proto__', sessionPage('__proto__')]])
  fixture.onRead(() => sourceSnapshot(10, remaining))
  fixture.send(sourceSnapshot(3, pages))
  fixture.frames.tick()
  const after = session.getSession()
  assert.deepEqual(Object.keys(after.pages), ['__proto__'])
  assert.equal(after.pages.__proto__, before.pages.__proto__)
  assert.equal(after.pages.constructor, undefined)
})

test('listeners read one installed snapshot and removal/addition during notification is safe', () => {
  const fixture = sessionFixture()
  const session = fixture.create()
  const seen = []
  let unsubscribeB
  let removeC
  const removeA = session.subscribe(() => {
    seen.push(['a', session.getSession()])
    unsubscribeB()
    if (!removeC) {
      removeC = session.subscribe(() => { seen.push(['c', session.getSession()]) })
      fixture.emit(sourceSnapshot(2, { a: sessionPage('a', { revision: 2 }) }))
    }
  })
  unsubscribeB = session.subscribe(() => { seen.push(['b', session.getSession()]) })
  fixture.emit(sourceSnapshot(1, { a: sessionPage('a') }))
  fixture.frames.tick()
  const first = session.getSession()
  assert.deepEqual(seen.map(([id]) => id), ['a'])
  assert.equal(seen[0][1], first)
  assert.equal(first.pages.a.revision, 1)
  assert.equal(fixture.frames.size, 1)
  fixture.frames.tick()
  assert.deepEqual(seen.map(([id]) => id), ['a', 'a', 'c'])
  assert.equal(seen[1][1], seen[2][1])
  assert.equal(seen[1][1].version, 2)
  removeA(); removeA(); removeC(); unsubscribeB()
  fixture.emit(sourceSnapshot(3, {}))
  fixture.frames.tick()
  assert.equal(seen.length, 3)
})

test('plain-store read-register-read reconciles a publication without eager delivery', () => {
  const fixture = sessionFixture()
  const session = fixture.create()
  const before = session.getSession()
  fixture.emit(sourceSnapshot(1, { a: sessionPage('a') }))
  fixture.frames.tick() // A publication between the host's initial read and registration.
  let calls = 0
  session.subscribe(() => { calls++ })
  assert.equal(calls, 0)
  assert.notEqual(session.getSession(), before)
  assert.equal(session.getSession().version, 1)
})

test('close marks native immediately, caches its promise and releases only after terminal publication', async () => {
  const fixture = sessionFixture(sourceSnapshot(0, { a: sessionPage('a') }))
  const session = fixture.create()
  let calls = 0
  session.subscribe(() => { calls++; assert.equal(session.getSession().closed, true) })
  const closing = session.close()
  assert.equal(session.close(), closing)
  assert.equal(fixture.closeCalls, 1)
  assert.equal(fixture.current.closed, true)
  assert.equal(session.getSession().closed, false)
  assert.equal(fixture.removals, 0)
  fixture.frames.tick()
  const terminal = session.getSession()
  assert.equal(terminal.closed, true)
  assert.equal(calls, 1)
  assert.equal(fixture.removals, 1)
  fixture.send(sourceSnapshot(20, { late: sessionPage('late') }))
  assert.equal(fixture.frames.size, 0)
  assert.equal(session.getSession(), terminal)
  fixture.finishClose()
  assert.equal(await closing, undefined)
  assert.equal(session.close(), closing)
  await assert.rejects(session.setFilter('a', 'none'), (error) => error.code === 'processor/disposed')
  session.subscribe(() => { calls++ })()
  assert.equal(calls, 1)
})

test('close reconciles terminal state when the final bridge callback is absent', async () => {
  const fixture = sessionFixture()
  fixture.suppressCloseEvent()
  const session = fixture.create()
  const closing = session.close()
  fixture.finishClose()
  await closing
  assert.equal(session.getSession().closed, true)
  assert.equal(fixture.frames.size, 0)
  fixture.frames.tick()
  assert.equal(session.getSession().closed, true)
  assert.equal(fixture.removals, 1)
})

test('ignored close failures are logged while awaiting callers retain the original rejection', async () => {
  const fixture = sessionFixture()
  const session = fixture.create()
  const warnings = []
  const warn = console.warn
  console.warn = (...args) => { warnings.push(args) }
  try {
    const closing = session.close()
    fixture.finishClose({ code: 'cache/delete-failed' })
    // Deliberately leave the returned rejection unawaited for a turn; the adapter owns its logger.
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(warnings.length, 1)
    const failure = warnings[0][1]
    assert.equal(failure instanceof errors.ScannerError, true)
    await assert.rejects(closing, (error) => error === failure && error.code === 'cache/delete-failed')
    assert.equal(session.close(), closing)
    fixture.frames.tick()
    assert.equal(session.getSession().closed, true)
    assert.equal(fixture.removals, 1)
  } finally { console.warn = warn }
})

test('listener exceptions do not interrupt publication or other listeners', () => {
  const fixture = sessionFixture()
  const session = fixture.create()
  const warn = console.warn
  const warnings = []
  console.warn = (...args) => { warnings.push(args) }
  try {
    let calls = 0
    session.subscribe(() => { throw new Error('listener') })
    session.subscribe(() => { calls++; assert.equal(session.getSession().version, calls) })
    fixture.emit(sourceSnapshot(1, { a: sessionPage('a') }))
    fixture.frames.tick()
    fixture.emit(sourceSnapshot(2, {}))
    fixture.frames.tick()
    assert.equal(calls, 2)
    assert.equal(warnings.length, 2)
  } finally { console.warn = warn }
})

test('the public session factory wraps admission, registration failure and stable store methods', async () => {
  const fixture = sessionFixture(sourceSnapshot(0, { a: sessionPage('a') }))
  let result = fixture.native
  const { DocumentScanner } = load('src/index.ts', {
    'react-native-nitro-modules': { NitroModules: { createHybridObject: () => ({ createSessionResult: () => result }) } },
    './ScannerError': errors,
    './ScanSession': { createScanSession: (native) => sessionAdapter.createScanSession(native, fixture.frames) },
    './DocumentScannerCamera': {}, './DocumentCropEditor': {},
    './ImageViewer': {}, './ImagePager': {}, './PageThumbnail': {}, './CameraPermission': {},
  })
  const session = DocumentScanner.createSession()
  const { getSession, subscribe, setDocumentCrop } = session
  assert.equal(getSession(), session.getSession())
  let calls = 0
  subscribe(() => { calls++ })
  assert.equal(calls, 0)
  assert.equal(await setDocumentCrop('a', FULL_SESSION_QUAD), undefined)
  assert.equal(await session.setShadowRemoval('a', false), undefined)
  assert.equal(await session.removePage('a'), undefined)
  await assert.rejects(session.setFilter('missing', 'none'), (error) => error.code === 'input/invalid')
  assert.deepEqual(fixture.calls.slice(0, 2), [['quad', 'a', FULL_SESSION_QUAD], ['shadows', 'a', false]])
  result = { code: 'processor/disposed' }
  assert.throws(() => DocumentScanner.createSession(), (error) => error instanceof errors.ScannerError && error.code === 'processor/disposed')
  let cleanup = 0
  result = { observeResult: () => ({ code: 'processor/disposed' }), closeResult: async () => { cleanup++; return true } }
  assert.throws(() => DocumentScanner.createSession(), (error) => error.code === 'processor/disposed')
  assert.equal(cleanup, 1)
  for (const code of ['processor/superseded', 'capture/busy', 'capture/not-ready']) {
    await assert.rejects(errors.scannerOperation(async () => ({ code }), 'capture/failed'), (error) => error.code === code)
  }
})

test('terminal publication cancels frames requested reentrantly and never replays late callbacks', async () => {
  const fixture = sessionFixture()
  const session = fixture.create()
  let calls = 0
  session.subscribe(() => {
    calls++
    fixture.send(sourceSnapshot(100, { stale: sessionPage('stale') }))
    assert.equal(session.getSession().closed, true)
  })
  const closing = session.close()
  fixture.frames.tick()
  assert.equal(fixture.frames.size, 0)
  assert.equal(fixture.removals, 1)
  fixture.frames.tick()
  assert.equal(calls, 1)
  fixture.finishClose()
  await closing
})

test('a failed gap read retains consistent state and retries on the next event', () => {
  const fixture = sessionFixture()
  const session = fixture.create()
  fixture.onRead(() => { throw new Error('bridge read') })
  const warn = console.warn
  const warnings = []
  console.warn = (...args) => { warnings.push(args) }
  try {
    fixture.send(sourceSnapshot(4, { a: sessionPage('a', { revision: 4 }) }))
    assert.equal(warnings.length, 1)
    assert.equal(warnings[0][1] instanceof errors.ScannerError, true)
    fixture.frames.tick()
    assert.equal(session.getSession().pages.a, undefined)
    assert.equal(session.getSession().version, 0)
    fixture.onRead(() => sourceSnapshot(5, { a: sessionPage('a', { revision: 5 }) }))
    fixture.send(sourceSnapshot(5, { a: sessionPage('a', { revision: 5 }) }))
    fixture.frames.tick()
    assert.equal(session.getSession().pages.a.revision, 5)
  } finally { console.warn = warn }
})

test('initial registration absorbs older buffered events and releases an already terminal owner', () => {
  const fixture = sessionFixture(sourceSnapshot(9, {}, true))
  fixture.onObserve(() => { fixture.send(sourceSnapshot(8, { a: sessionPage('a') })) })
  const session = fixture.create()
  assert.equal(session.getSession().closed, true)
  assert.equal(session.getSession().version, 0)
  assert.equal(fixture.removals, 1)
  assert.equal(fixture.frames.size, 0)
  fixture.send(sourceSnapshot(10, {}))
  assert.equal(fixture.frames.size, 0)
})

test('camera keeps capture-state callback identity and forwards only committed handlers and ID results', async () => {
  const fixture = sessionFixture()
  const session = fixture.create()
  const slots = []
  let cursor = 0
  let effects = []
  const ref = { current: null }
  const useMemo = (create, deps) => {
    const index = cursor++
    const old = slots[index]
    if (!old || deps.some((value, key) => !Object.is(value, old.deps[key]))) {
      slots[index] = { value: create(), deps }
    }
    return slots[index].value
  }
  const { Camera } = load('src/DocumentScannerCamera.tsx', {
    'react': {
      forwardRef: (render) => render,
      useLayoutEffect: (effect) => { effects.push(effect) },
      useMemo,
      useRef: (value) => { const index = cursor++; return slots[index] ?? (slots[index] = { current: value }) },
      useImperativeHandle: (target, create) => { target.current = create() },
    },
    'react/jsx-runtime': { jsx: (type, props) => ({ type, props }) },
    'react-native': { processColor: () => 0 },
    'react-native-nitro-modules': { callback: (fn) => fn, getHostComponent: () => 'NativeCamera' },
    '../nitrogen/generated/shared/json/DocumentScannerCameraViewConfig.json': {},
    './ScannerError': errors, './ScanSession': sessionAdapter,
  })
  const seen = []
  const stages = []
  let renderOptions
  const render = (handler) => {
    cursor = 0; effects = []
    return Camera({ session, renderOptions, quadTensorModel: '/model', onCaptureStateChange: handler, onCaptureStage: (stage, reason) => stages.push([stage, reason]) }, ref)
  }
  const first = render((value) => seen.push(['first', value]))
  assert.deepEqual(first.props.renderOptions, {})
  renderOptions = { filter: 'grayscale', removeShadows: false }
  effects.forEach((effect) => effect())
  const oldCallback = first.props.onCaptureStateChange
  oldCallback('ready')
  const second = render((value) => seen.push(['second', value]))
  assert.equal(second.props.renderOptions, renderOptions)
  assert.equal(second.props.onCaptureStateChange, oldCallback)
  assert.equal(second.props.onCaptureStage, first.props.onCaptureStage)
  first.props.onCaptureStage('waiting', 'document')
  first.props.onCaptureStage('waiting', 'light')
  first.props.onCaptureStage('shooting')
  assert.deepEqual(stages, [['waiting', 'document'], ['waiting', 'light'], ['shooting', undefined]])
  oldCallback('waiting') // Render is not yet committed.
  effects.forEach((effect) => effect())
  oldCallback('saving')
  assert.deepEqual(seen, [['first', 'ready'], ['first', 'waiting'], ['second', 'saving']])
  const requests = []
  second.props.hybridRef({
    takePhotoResult: async (options) => { requests.push(options); fixture.emit(sourceSnapshot(1, { 'native-page-id': sessionPage('native-page-id') })); return 'native-page-id' },
  })
  const options = { skipQualityGate: true }
  assert.equal(await ref.current.takePhoto(options), 'native-page-id')
  assert.deepEqual(requests, [options])
  assert.ok(session.getSession().pages['native-page-id'])
  assert.equal(fixture.frames.size, 0)
  second.props.hybridRef({ takePhotoResult: async () => { fixture.emit(sourceSnapshot(2)); return 'native-page-id' } })
  await assert.rejects(ref.current.takePhoto(), error => error.code === 'processor/superseded')
  second.props.hybridRef({ takePhotoResult: async () => ({ code: 'capture/busy' }) })
  await assert.rejects(ref.current.takePhoto(), (error) => error instanceof errors.ScannerError && error.code === 'capture/busy')
  const absent = render(undefined)
  effects.forEach((effect) => effect())
  assert.notEqual(absent.props.onCaptureStateChange, oldCallback)
  assert.throws(() => sessionAdapter.getNativeScanSession({}), (error) => error.code === 'input/invalid')
})


test('crop ref reads the native draft and applies presets without React geometry callbacks', async () => {
  const ref = { current: null }
  const { CropEditor } = load('src/DocumentCropEditor.tsx', {
    react: { forwardRef: fn => fn, useRef: value => ({ current: value }),
      useMemo: fn => fn(), useLayoutEffect: fn => fn(),
      useImperativeHandle: (target, fn) => { target.current = fn() } },
    'react/jsx-runtime': { jsx }, 'react-native-nitro-modules': { callback: fn => fn, getHostComponent: () => 'Editor' },
    './ScanSession': sessionAdapter, './ScannerError': errors,
    '../nitrogen/generated/shared/json/DocumentCropEditorViewConfig.json': {},
  })
  const f = sessionFixture(sourceSnapshot(0, { p: sessionPage('p') }))
  const observedPresets = []
  const editor = CropEditor({ session: f.create(), pageId: 'p', onPresetChange: preset => observedPresets.push(preset) }, ref)
  editor.props.onPresetChange('none')
  editor.props.onPresetChange('custom')
  assert.deepEqual(observedPresets, ['none', 'custom'])
  let draft = FULL_SESSION_QUAD; const presets = []
  let submissions = 0
  editor.props.hybridRef({ getQuadResult: async () => draft,
    submitResult: async () => { submissions++; return true },
    setPresetResult: async preset => { presets.push(preset); return true } })
  assert.equal('quad' in editor.props, false)
  assert.equal('onQuadChange' in editor.props, false)
  await ref.current.setPreset('none')
  assert.deepEqual(presets, ['none'])
  assert.equal(await ref.current.getQuad(), draft)
  await ref.current.submit()
  assert.equal(submissions, 1)
  draft = { code: 'input/invalid' }
  await assert.rejects(ref.current.getQuad(), error => error.code === 'input/invalid')
  editor.props.hybridRef(null)
  await assert.rejects(ref.current.setPreset('auto'), error => error.code === 'input/invalid')
})

test('session views keep committed callbacks stable across page, direction and session changes', () => {
  for (const [file, exported, config] of [
    ['ImageViewer', 'ImageViewer', 'ImageViewerView'],
    ['ImagePager', 'ImagePager', 'ImagePagerView'],
    ['DocumentCropEditor', 'CropEditor', 'DocumentCropEditorView'],
  ]) {
    const firstFixture = sessionFixture()
    const secondFixture = sessionFixture()
    const firstSession = firstFixture.create()
    const secondSession = secondFixture.create()
    const slots = []
    let cursor = 0
    let effects = []
    const component = load(`src/${file}.tsx`, {
      'react': {
        forwardRef: (render) => render,
        useLayoutEffect: (effect) => effects.push(effect),
        useMemo: (create, deps) => {
          const index = cursor++
          const old = slots[index]
          if (!old || deps.some((value, key) => !Object.is(value, old.deps[key]))) slots[index] = { value: create(), deps }
          return slots[index].value
        },
        useRef: (value) => { const index = cursor++; return slots[index] ?? (slots[index] = { current: value }) },
        useImperativeHandle: () => {},
      },
      'react/jsx-runtime': { jsx: (type, props) => ({ type, props }) },
      'react-native-nitro-modules': { callback: (fn) => fn, getHostComponent: () => 'NativeView' },
      [`../nitrogen/generated/shared/json/${config}Config.json`]: {},
      './ScannerError': errors, './ScanSession': sessionAdapter,
    })[exported]
    const seen = []
    const render = (session, props, name) => {
      cursor = 0; effects = []
      return component({ session, pageId: 'page', pageIds: ['page'], selectedPageId: 'page',
        onLoad: (...args) => seen.push([name, ...args]),
        onError: (error) => seen.push([name, error.code]), ...props }, null)
    }
    const first = render(firstSession, {}, 'first')
    effects.forEach((effect) => effect())
    const second = render(secondSession, { direction: 'rtl', pageId: 'other', pageIds: ['other', 'page'] }, 'second')
    assert.equal(first.props.onLoad, second.props.onLoad)
    assert.equal(first.props.onError, second.props.onError)
    assert.notEqual(first.props.session, second.props.session)
    second.props.onLoad(...(file === 'DocumentCropEditor' ? [40, 30] : ['page']))
    effects.forEach((effect) => effect())
    second.props.onLoad(...(file === 'DocumentCropEditor' ? [80, 60] : ['other']))
    second.props.onError({ code: 'input/invalid' })
    assert.deepEqual(seen, file === 'DocumentCropEditor'
      ? [['first', 40, 30], ['second', 80, 60], ['second', 'input/invalid']]
      : [['first', 'page'], ['second', 'other'], ['second', 'input/invalid']])
    if (file === 'ImagePager') {
      assert.equal(first.props.direction, 'auto')
      assert.equal(second.props.direction, 'rtl')
      assert.deepEqual(second.props.pageIds, ['other', 'page'])
    } else assert.equal(second.props.pageId, 'other')
    assert.equal('uri' in second.props, false)
    assert.equal('originalUri' in second.props, false)
    assert.equal('uris' in second.props, false)
    assert.throws(() => render({}, {}, 'invalid'), (error) => error.code === 'input/invalid')
  }
})


test('thumbnail tiles bind sessions directly while host style and interactions remain composable', () => {
  const fixture = sessionFixture()
  const session = fixture.create()
  const { PageThumbnail } = load('src/PageThumbnail.tsx', {
    'react-native-nitro-modules': { getHostComponent: () => 'NativeThumbnail' },
    'react/jsx-runtime': { jsx: (type, props) => ({ type, props }) },
    './ScanSession': sessionAdapter,
    '../nitrogen/generated/shared/json/PageThumbnailViewConfig.json': {},
  })
  const style = { width: 48, height: 64 }
  const tile = PageThumbnail({ session, pageId: 'a', style, accessibilityLabel: 'Page 1' })
  assert.equal(tile.props.resizeMode, 'cover')
  assert.equal(tile.props.style, style)
  assert.equal(tile.props.accessibilityLabel, 'Page 1')
  const next = PageThumbnail({ session, pageId: 'b', style, resizeMode: 'contain' })
  assert.equal(next.props.session, tile.props.session)
  assert.equal(next.props.pageId, 'b')
  assert.equal(next.props.resizeMode, 'contain')
  assert.deepEqual(Object.keys(next.props).sort(), ['pageId', 'resizeMode', 'session', 'style'])
  assert.equal(fixture.frames.size, 0)
  assert.throws(() => PageThumbnail({ session: {}, pageId: 'a' }), (error) => error.code === 'input/invalid')
})


test('session export facade forwards ordered IDs and options immediately and returns independent artifacts', async () => {
  const fixture = sessionFixture()
  const native = sessionAdapter.getNativeScanSession(fixture.create())
  const session = fixture.create()
  // Both stores wrap the same test transport; no published metadata is needed for native admission.
  const calls = []
  native.createPdfResult = (ids, options) => { calls.push(['pdf', [...ids], options]); return Promise.resolve('file:///exports/one.pdf') }
  native.exportImagesResult = (ids, directory) => { calls.push(['images', [...ids], directory]); return Promise.resolve(['file:///exports/a.png', 'file:///exports/b.jpg']) }
  native.saveCaptureSampleResult = (...args) => { calls.push(['sample', ...args]); return Promise.resolve('sample.jpg') }
  const options = { outputPath: 'file:///exports/one.pdf', targetDpi: 200, pageWidth: 595, pageHeight: 842 }
  const pdf = session.createPdf(['b', 'a', 'b'], options)
  assert.deepEqual(calls[0], ['pdf', ['b', 'a', 'b'], options])
  assert.equal(await pdf, 'file:///exports/one.pdf')
  assert.deepEqual(await session.exportImages(['a', 'b'], 'file:///exports'), ['file:///exports/a.png', 'file:///exports/b.jpg'])
  // Samples carry only the inference input and prediction; no page outline crosses the bridge.
  assert.equal(await session.saveCaptureSample('a'), 'sample.jpg')
  assert.deepEqual(calls.slice(1), [['images', ['a', 'b'], 'file:///exports'], ['sample', 'a']])
  native.createPdfResult = async () => ({ code: 'analysis/failed' })
  native.exportImagesResult = async () => ({ code: 'processor/disposed' })
  native.saveCaptureSampleResult = () => { throw new Error('bridge unavailable') }
  await assert.rejects(session.createPdf(['a'], options), (error) => error instanceof errors.ScannerError && error.code === 'analysis/failed')
  await assert.rejects(session.exportImages(['a'], '/exports'), (error) => error.code === 'processor/disposed')
  await assert.rejects(session.saveCaptureSample('a'), (error) => error.code === 'storage/write-failed' && error.cause.message === 'bridge unavailable')
  assert.equal(session.getSession().version, 0)
})


test('session order changes notify subscribers without replacing page metadata', () => {
  const f = sessionFixture(sourceSnapshot(0, { a: sessionPage('a'), b: sessionPage('b') }))
  const session = f.create()
  const before = session.getSession()
  let notifications = 0
  session.subscribe(() => notifications++)
  f.emit({ ...f.current, sourceVersion: 1, pageIds: ['b', 'a'] })
  f.frames.tick()
  assert.deepEqual(session.getSession().pageIds, ['b', 'a'])
  assert.equal(session.getSession().pages, before.pages)
  assert.equal(notifications, 1)
  f.emit({ ...f.current, sourceVersion: 2, pageIds: ['a', 'b'] })
  f.emit({ ...f.current, sourceVersion: 3, pageIds: ['b', 'a'] })
  f.frames.tick()
  assert.equal(notifications, 1)
})

test('exports omit page IDs when using native session order', async () => {
  const f = sessionFixture()
  const calls = []
  f.native.createPdfResult = async (...args) => { calls.push(args); return 'pdf' }
  f.native.exportImagesResult = async (...args) => { calls.push(args); return ['image'] }
  const session = f.create()
  await session.createPdf({ outputPath: '/external/out.pdf' })
  await session.exportImages('/external/images')
  assert.deepEqual(calls, [[undefined, { outputPath: '/external/out.pdf' }], [undefined, '/external/images']])
})


test('superseded operations reject as cancellation, separately from scanner failures', async () => {
  for (const code of ['processor/superseded', 'model/superseded']) {
    await assert.rejects(errors.scannerOperation(async () => ({ code }), 'capture/failed'), error =>
      error instanceof errors.ScannerCancellation && !(error instanceof errors.ScannerError) &&
      error.name === 'AbortError' && error.code === code)
  }
  await assert.rejects(errors.scannerOperation(async () => { throw new errors.ScannerCancellation('processor/superseded') }, 'capture/failed'),
    error => error instanceof errors.ScannerCancellation)
})
