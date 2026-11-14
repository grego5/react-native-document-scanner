package com.margelo.nitro.documentscanner.processing

import android.content.ContextWrapper
import com.margelo.nitro.documentscanner.ScannerErrorCode
import com.margelo.nitro.documentscanner.ScannerException
import org.junit.Assert.*
import org.junit.Test

class CornerTensorModelLoaderStatusTest {
  /** The fake prepare never reads it. */
  private val context = ContextWrapper(null)
  private val worker = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
  private val prepared = java.util.Collections.synchronizedList(mutableListOf<String>())
  private var activated: CornerModelStore.Selection? = null
  private val failing = mutableSetOf<String>()

  private val loader = CornerTensorModelLoader(
    prepare = { _, source ->
      prepared += source
      if (source in failing) throw ScannerException(ScannerErrorCode.MODEL_LOAD_FAILED)
      CornerModelStore.Selection(byteArrayOf(1))
    },
    activate = { activated = it },
    active = { activated },
    bundledDefault = { DEFAULT },
    worker = { worker.add(it) },
  )

  private fun drain() { while (true) (worker.poll() ?: return).run() }
  private fun load(source: String): MutableList<Throwable?> =
    mutableListOf<Throwable?>().also { out -> loader.load(context, source) { out += it } }
  /** A camera without a source, which follows the packaged [DEFAULT]. */
  private fun followDefault(): MutableList<Throwable?> =
    mutableListOf<Throwable?>().also { out -> loader.loadCameraSource(context, "") { out += it } }
  private fun waited(): MutableList<Result<Boolean>> = mutableListOf<Result<Boolean>>().also { out -> loader.await { out += it } }
  private fun code(error: Throwable?) = (error as ScannerException).code

  @Test fun theDefaultLoadsOnceWithoutACameraAndLaterRequestsJoinIt() {
    assertSame(CornerModelStatus.Absent, loader.current)
    assertEquals(listOf(Result.success(false)), waited())
    val first = followDefault()
    val second = followDefault()
    val waiting = waited()
    assertTrue(loader.current is CornerModelStatus.Loading)
    drain()
    assertEquals(listOf(DEFAULT), prepared)
    assertEquals(listOf<Throwable?>(null), first)
    assertEquals(listOf<Throwable?>(null), second)
    assertEquals(listOf(Result.success(true)), waiting)
    assertEquals(listOf<Throwable?>(null), followDefault())
    assertEquals(listOf<Throwable?>(null), load(DEFAULT))
    drain()
    assertEquals("A ready same-source request reuses its selection.", listOf(DEFAULT), prepared)
  }

  @Test fun repeatedHeadlessStartsJoinOnePendingDefault() {
    repeat(3) { loader.startDefault(context) }
    assertEquals(DEFAULT, (loader.current as CornerModelStatus.Loading).source)
    val waiting = waited()
    drain()
    assertEquals(listOf(DEFAULT), prepared)
    assertEquals(listOf(Result.success(true)), waiting)

    val bare = CornerTensorModelLoader(prepare = { _, _ -> throw AssertionError("Nothing loads.") },
      bundledDefault = { null }, worker = { throw AssertionError("Nothing loads.") })
    bare.startDefault(context)
    assertSame("Without a packaged model nothing starts.", CornerModelStatus.Absent, bare.current)
  }

  @Test fun aPendingDefaultFollowsTheExplicitReplacementWhichTheDefaultNeverReplaces() {
    // A camera without an explicit source whose props never change.
    val camera = mutableListOf<Throwable?>()
    loader.loadCameraSource(context, "") { camera += it }
    val waiting = waited()
    val explicit = load(CUSTOM)
    assertEquals("The camera waits for the replacement.", emptyList<Throwable?>(), camera)
    assertEquals(ScannerErrorCode.MODEL_SUPERSEDED, code(waiting.single().exceptionOrNull()))
    drain()
    assertEquals("The superseded default never prepares.", listOf(CUSTOM), prepared)
    assertEquals(listOf<Throwable?>(null), explicit)
    assertEquals(listOf<Throwable?>(null), camera)
    assertEquals(listOf<Throwable?>(null), followDefault())
    drain()
    assertEquals(CUSTOM, (loader.current as CornerModelStatus.Ready).source)
    assertEquals(listOf(CUSTOM), prepared)
  }

  @Test fun anExplicitPackagedSourceReplacesACustomModel() {
    load(CUSTOM)
    drain()
    val explicit = mutableListOf<Throwable?>()
    loader.loadCameraSource(context, DEFAULT) { explicit += it }
    drain()
    assertEquals(listOf<Throwable?>(null), explicit)
    assertEquals(DEFAULT, (loader.current as CornerModelStatus.Ready).source)
    assertEquals(listOf(CUSTOM, DEFAULT), prepared)
  }

  @Test fun aCameraWithoutAnySourceOrPackagedModelIsInvalid() {
    val bare = CornerTensorModelLoader(prepare = { _, _ -> throw AssertionError("Nothing loads.") },
      bundledDefault = { null }, worker = { throw AssertionError("Nothing loads.") })
    val result = mutableListOf<Throwable?>()
    bare.loadCameraSource(context, "") { result += it }
    assertTrue(result.single() is com.margelo.nitro.documentscanner.InvalidInputException)
    assertSame(CornerModelStatus.Absent, bare.current)
  }

  @Test fun failuresAreTypedAndAttributedAndOnlyAnExplicitRequestRetries() {
    failing += CUSTOM
    val explicit = load(CUSTOM)
    drain()
    assertEquals(ScannerErrorCode.MODEL_LOAD_FAILED, code(explicit.single()))
    val failed = loader.current as CornerModelStatus.Failed
    assertEquals(CUSTOM, failed.source)
    assertEquals(ScannerErrorCode.MODEL_LOAD_FAILED, code(waited().single().exceptionOrNull()))
    assertEquals(ScannerErrorCode.MODEL_LOAD_FAILED, code(followDefault().single()))
    drain()
    assertEquals("The default never replaces a failed explicit request.", listOf(CUSTOM), prepared)
    failing.clear()
    val retry = load(CUSTOM)
    drain()
    assertEquals(listOf<Throwable?>(null), retry)
    assertEquals(listOf(CUSTOM, CUSTOM), prepared)
  }

  @Test fun observersSeeTheCurrentStatusThenEveryChangeInOrderUntilRemoved() {
    val seen = mutableListOf<String>()
    fun name(status: CornerModelStatus) = when (status) {
      CornerModelStatus.Absent -> "absent"
      is CornerModelStatus.Loading -> "loading:${status.source}"
      is CornerModelStatus.Ready -> "ready:${status.source}"
      is CornerModelStatus.Failed -> "failed:${status.source}"
    }
    val remove = loader.observe { seen += name(it) }
    followDefault()
    load(CUSTOM)
    drain()
    assertEquals(listOf("absent", "loading:$DEFAULT", "loading:$CUSTOM", "ready:$CUSTOM"), seen)
    remove()
    remove()
    load(DEFAULT)
    drain()
    assertEquals(4, seen.size)
  }

  @Test fun aListenerCanRequestAReplacementAndSeesItsLatestStatusAfterwards() {
    val seen = mutableListOf<CornerModelStatus>()
    var calls = 0
    var inside = false
    loader.observe { status ->
      assertFalse("Calls never overlap.", inside)
      inside = true
      calls++
      seen += status
      if (status is CornerModelStatus.Loading && status.source == DEFAULT) loader.load(context, CUSTOM)
      inside = false
    }
    followDefault()
    drain()
    assertEquals(CUSTOM, (seen.last() as CornerModelStatus.Ready).source)
    assertEquals(listOf(CUSTOM), prepared)
    assertEquals(seen.size, calls)
  }

  @Test fun concurrentObserversAllEndOnTheFinalStatus() {
    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
    val lasts = List(16) { java.util.concurrent.atomic.AtomicReference<CornerModelStatus>() }
    val ready = java.util.concurrent.CountDownLatch(1)
    val futures = lasts.mapIndexed { index, last ->
      pool.submit {
        ready.await()
        loader.observe { last.set(it) }
        if (index % 4 == 0) loader.load(context, "file:///models/$index.tflite")
      }
    }
    ready.countDown()
    futures.forEach { it.get() }
    pool.shutdown()
    drain()
    val final = loader.current
    assertTrue(final is CornerModelStatus.Ready)
    lasts.forEach { assertSame(final, it.get()) }
  }

  private companion object {
    const val DEFAULT = CornerTensorModelLoader.DEFAULT_SOURCE
    const val CUSTOM = "file:///models/custom.tflite"
  }
}
