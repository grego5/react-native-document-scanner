package com.margelo.nitro.documentscanner

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ScannerWorkQueueTest {
  private val worker = Executors.newSingleThreadExecutor()
  private val callbackErrors = Collections.synchronizedList(mutableListOf<Throwable>())
  private val queue = ScannerWorkQueue(worker) { callbackErrors.add(it) }

  @After fun stopWorker() {
    worker.shutdownNow()
    assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
  }

  private class Blocker {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    fun run() {
      started.countDown()
      assertTrue("Blocked operation was not released", release.await(10, TimeUnit.SECONDS))
    }
  }

  private fun await(latch: CountDownLatch) {
    assertTrue("Queue did not settle", latch.await(5, TimeUnit.SECONDS))
  }

  private fun close(owner: ScannerWorkQueue.Owner) {
    queue.close(owner).get(5, TimeUnit.SECONDS)
  }

  @Test fun admitsMoreThanTwoJobsAndExecutesInFifoOrder() {
    val owner = queue.newOwner()
    val blocker = Blocker()
    val order = Collections.synchronizedList(mutableListOf<Int>())
    val settled = CountDownLatch(101)
    val failures = Collections.synchronizedList(mutableListOf<Throwable>())
    queue.submit(owner, ScannerWorkQueue.Kind.ANALYSIS,
      operation = { blocker.run(); order.add(0) },
      onSuccess = { settled.countDown() }, onFailure = { failures.add(it); settled.countDown() })
    await(blocker.started)
    for (index in 1..100) {
      queue.submit(owner, ScannerWorkQueue.Kind.RENDER,
        operation = { order.add(index) },
        onSuccess = { settled.countDown() }, onFailure = { failures.add(it); settled.countDown() })
    }
    assertEquals(101L, settled.count)
    blocker.release.countDown()
    await(settled)
    close(owner)
    assertEquals((0..100).toList(), order)
    assertTrue(failures.isEmpty())
    assertTrue(callbackErrors.isEmpty())
  }

  @Test fun replacementIsQueuedOnlyAndIsolatedByKindPageAndSession() {
    val a = queue.newOwner()
    val b = queue.newOwner()
    val blocker = Blocker()
    val order = Collections.synchronizedList(mutableListOf<String>())
    val settled = CountDownLatch(6)
    val failures = Collections.synchronizedList(mutableListOf<Pair<String, Throwable>>())
    fun add(owner: ScannerWorkQueue.Owner, kind: ScannerWorkQueue.Kind, page: String, name: String,
      action: () -> Unit = {}) {
      queue.submit(owner, kind, page,
        operation = { action(); order.add(name) },
        onSuccess = { settled.countDown() },
        onFailure = { failures.add(name to it); settled.countDown() })
    }
    add(a, ScannerWorkQueue.Kind.RENDER, "page", "running") { blocker.run() }
    await(blocker.started)
    add(a, ScannerWorkQueue.Kind.RENDER, "page", "superseded")
    add(a, ScannerWorkQueue.Kind.ANALYSIS, "page", "analysis")
    add(a, ScannerWorkQueue.Kind.RENDER, "other", "other-page")
    add(b, ScannerWorkQueue.Kind.RENDER, "page", "other-session")
    add(a, ScannerWorkQueue.Kind.RENDER, "page", "latest")
    assertEquals(1, failures.size)
    assertEquals("superseded", failures.single().first)
    assertEquals(ScannerErrorCode.PROCESSOR_SUPERSEDED, (failures.single().second as ScannerException).code)
    blocker.release.countDown()
    await(settled)
    close(a)
    close(b)
    assertEquals(listOf("running", "analysis", "other-page", "other-session", "latest"), order)
    assertEquals(1, failures.size)
    assertTrue(callbackErrors.isEmpty())
  }

  @Test fun closeCancelsOnlyItsOwnerAndDiscardsRunningResultBeforeCompletingBarrier() {
    val a = queue.newOwner()
    val b = queue.newOwner()
    val blocker = Blocker()
    val successes = AtomicInteger()
    val discarded = AtomicInteger()
    val failureCount = AtomicInteger()
    val otherFinished = CountDownLatch(1)
    val failures = Collections.synchronizedList(mutableListOf<ScannerErrorCode>())
    fun failure(error: Throwable) {
      failures.add((error as ScannerException).code)
      failureCount.incrementAndGet()
    }
    queue.submit(a, ScannerWorkQueue.Kind.RENDER, "page",
      operation = { blocker.run(); "unpublished image" },
      onSuccess = { successes.incrementAndGet() }, onFailure = ::failure,
      onDiscard = { assertEquals("unpublished image", it); discarded.incrementAndGet() })
    await(blocker.started)
    queue.submit(a, ScannerWorkQueue.Kind.ANALYSIS, "page",
      operation = { fail("Cancelled queued operation ran") },
      onSuccess = { fail("Cancelled queued operation succeeded") }, onFailure = ::failure)
    queue.submit(b, ScannerWorkQueue.Kind.ANALYSIS, "page",
      operation = { "other session" },
      onSuccess = { otherFinished.countDown() }, onFailure = { callbackErrors.add(it) })
    val barrier = queue.close(a)
    assertSame(barrier, queue.close(a))
    assertFalse(barrier.isDone)
    assertEquals(1, failureCount.get())
    blocker.release.countDown()
    barrier.get(5, TimeUnit.SECONDS)
    await(otherFinished)
    close(b)
    assertEquals(0, successes.get())
    assertEquals(1, discarded.get())
    assertEquals(2, failureCount.get())
    assertEquals(listOf(ScannerErrorCode.PROCESSOR_DISPOSED, ScannerErrorCode.PROCESSOR_DISPOSED), failures)

    queue.submit(a, ScannerWorkQueue.Kind.PDF,
      operation = { fail("Closed owner admitted work") },
      onSuccess = { fail("Closed owner published work") }, onFailure = ::failure)
    assertEquals(3, failureCount.get())
    assertTrue(callbackErrors.isEmpty())
  }

  @Test fun runningFailureAfterCloseSettlesAsDisposedAndDoesNotDiscardANonexistentResult() {
    val owner = queue.newOwner()
    val blocker = Blocker()
    var failure: Throwable? = null
    val discarded = AtomicInteger()
    queue.submit(owner, ScannerWorkQueue.Kind.RENDER,
      operation = { blocker.run(); throw IllegalStateException("operation failed") },
      onSuccess = { fail("Failure succeeded") }, onFailure = { failure = it },
      onDiscard = { discarded.incrementAndGet() })
    await(blocker.started)
    val barrier = queue.close(owner)
    blocker.release.countDown()
    barrier.get(5, TimeUnit.SECONDS)
    assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (failure as ScannerException).code)
    assertEquals(0, discarded.get())
    assertTrue(callbackErrors.isEmpty())
  }

  @Test fun actionsAndCallbacksCanAdmitFromAnotherThreadWithoutHoldingTheLock() {
    val owner = queue.newOwner()
    val other = queue.newOwner()
    val blocker = Blocker()
    val settled = CountDownLatch(4)
    fun proveAdmissionIsUnlocked() {
      val admitted = CountDownLatch(1)
      val producer = thread {
        queue.submit(other, ScannerWorkQueue.Kind.PDF,
          operation = {}, onSuccess = {}, onFailure = { callbackErrors.add(it) })
        admitted.countDown()
      }
      await(admitted)
      producer.join(5000)
      assertFalse(producer.isAlive)
    }
    queue.submit(owner, ScannerWorkQueue.Kind.ANALYSIS,
      operation = { proveAdmissionIsUnlocked(); blocker.run() },
      onSuccess = { proveAdmissionIsUnlocked(); settled.countDown() },
      onFailure = { callbackErrors.add(it); settled.countDown() })
    await(blocker.started)
    queue.submit(owner, ScannerWorkQueue.Kind.RENDER, "page",
      operation = { fail("Replaced job ran") }, onSuccess = { fail("Replaced job succeeded") },
      onFailure = { proveAdmissionIsUnlocked(); settled.countDown() })
    queue.submit(owner, ScannerWorkQueue.Kind.RENDER, "page",
      operation = {}, onSuccess = { settled.countDown() },
      onFailure = { callbackErrors.add(it); settled.countDown() })
    // Cancellation settlement has the same outside-lock guarantee as replacement.
    val cancelled = queue.newOwner()
    queue.submit(cancelled, ScannerWorkQueue.Kind.RENDER,
      operation = { fail("Cancelled job ran") }, onSuccess = { fail("Cancelled job succeeded") },
      onFailure = { proveAdmissionIsUnlocked(); settled.countDown() })
    close(cancelled)
    blocker.release.countDown()
    await(settled)
    val otherDrained = CountDownLatch(1)
    queue.submit(other, ScannerWorkQueue.Kind.PDF,
      operation = {}, onSuccess = { otherDrained.countDown() }, onFailure = { callbackErrors.add(it) })
    await(otherDrained)
    close(owner)
    close(other)
    assertTrue(callbackErrors.toString(), callbackErrors.isEmpty())
  }

  @Test fun callbacksCanCloseTheirOwnerWithoutWaitingOnTheImageWorker() {
    val owner = queue.newOwner()
    val settled = CountDownLatch(1)
    var barrier: java.util.concurrent.CompletableFuture<Unit>? = null
    queue.submit(owner, ScannerWorkQueue.Kind.ANALYSIS,
      operation = { 42 },
      onSuccess = {
        assertEquals(42, it)
        barrier = queue.close(owner)
        assertFalse(barrier!!.isDone)
        settled.countDown()
      }, onFailure = { callbackErrors.add(it); settled.countDown() })
    await(settled)
    barrier!!.get(5, TimeUnit.SECONDS)
    assertTrue(callbackErrors.isEmpty())
  }

  @Test fun callbackExceptionsDoNotStrandLaterJobsOrTheCloseBarrier() {
    val owner = queue.newOwner()
    val settled = CountDownLatch(1)
    val original = IllegalArgumentException("operation")
    queue.submit(owner, ScannerWorkQueue.Kind.ANALYSIS,
      operation = { 1 }, onSuccess = { throw IllegalStateException("success callback") },
      onFailure = { fail("Success callback failure must not settle again") })
    queue.submit(owner, ScannerWorkQueue.Kind.RENDER,
      operation = { throw original }, onSuccess = { fail("Failure succeeded") },
      onFailure = { assertSame(original, it); throw IllegalStateException("failure callback") })
    queue.submit(owner, ScannerWorkQueue.Kind.PDF,
      operation = {}, onSuccess = { settled.countDown() }, onFailure = { fail(it.toString()) })
    await(settled)
    close(owner)
    assertEquals(listOf("success callback", "failure callback"), callbackErrors.map { it.message })
  }

  @Test fun concurrentAdmissionReplacementAndCloseSettleEveryRequestOnce() {
    val owner = queue.newOwner()
    val blocker = Blocker()
    queue.submit(owner, ScannerWorkQueue.Kind.ANALYSIS,
      operation = { blocker.run() }, onSuccess = {}, onFailure = {})
    await(blocker.started)
    val callbacks = Array(200) { AtomicInteger() }
    val completed = CountDownLatch(callbacks.size)
    val start = CountDownLatch(1)
    val halfway = CountDownLatch(1)
    val producers = (0 until 4).map { producer ->
      thread {
        await(start)
        repeat(50) { item ->
          val index = producer * 50 + item
          queue.submit(owner, ScannerWorkQueue.Kind.RENDER, "page-${item % 3}",
            operation = { index },
            onSuccess = { callbacks[index].incrementAndGet(); completed.countDown() },
            onFailure = {
              val code = (it as ScannerException).code
              assertTrue(code == ScannerErrorCode.PROCESSOR_SUPERSEDED || code == ScannerErrorCode.PROCESSOR_DISPOSED)
              callbacks[index].incrementAndGet()
              completed.countDown()
            })
          if (item == 10) halfway.countDown()
        }
      }
    }
    start.countDown()
    await(halfway)
    val barrier = queue.close(owner)
    producers.forEach { it.join(5000); assertFalse(it.isAlive) }
    await(completed)
    assertFalse(barrier.isDone)
    blocker.release.countDown()
    barrier.get(5, TimeUnit.SECONDS)
    assertTrue(callbacks.all { it.get() == 1 })
    assertTrue(callbackErrors.toString(), callbackErrors.isEmpty())
  }

  @Test fun throwingDiscardStillRejectsOnceAndCompletesClose() {
    val owner = queue.newOwner()
    val blocker = Blocker()
    val failures = AtomicInteger()
    queue.submit(owner, ScannerWorkQueue.Kind.RENDER,
      operation = { blocker.run(); "image" },
      onSuccess = { fail("Stale result was published") },
      onFailure = {
        assertEquals(ScannerErrorCode.PROCESSOR_DISPOSED, (it as ScannerException).code)
        failures.incrementAndGet()
      },
      onDiscard = { throw IllegalStateException("discard cleanup") })
    await(blocker.started)
    val barrier = queue.close(owner)
    blocker.release.countDown()
    barrier.get(5, TimeUnit.SECONDS)
    assertEquals(1, failures.get())
    assertEquals(listOf("discard cleanup"), callbackErrors.map { it.message })
  }
  @Test fun executorRejectionSettlesOnceAndAllowsTheOwnerToClose() {
    val failures = mutableListOf<Throwable>()
    val rejected = ScannerWorkQueue({ throw RejectedExecutionException("unavailable") }) { callbackErrors.add(it) }
    val owner = rejected.newOwner()
    rejected.submit(owner, ScannerWorkQueue.Kind.ANALYSIS,
      operation = { fail("Rejected worker ran") }, onSuccess = { fail("Rejected worker succeeded") },
      onFailure = { failures.add(it) })
    rejected.close(owner).get(5, TimeUnit.SECONDS)
    assertEquals(1, failures.size)
    assertEquals(ScannerErrorCode.PROCESSOR_UNAVAILABLE, (failures.single() as ScannerException).code)
    assertTrue(failures.single().cause is RejectedExecutionException)
    assertTrue(callbackErrors.isEmpty())
  }
}
