package com.margelo.nitro.documentscanner.processing

import android.net.Uri
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CachedImageFilesTest {
  private val base = File(
    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
    "cached-image-files-test-${UUID.randomUUID()}",
  )
  private val root = File(base, CachedImageFiles.DIRECTORY_NAME).apply { mkdirs() }
  private val outside = File(base, "outside").apply { mkdirs() }

  @After
  fun cleanup() {
    base.deleteRecursively()
  }

  @Test
  fun deletesOwnedFilesAndSkipsMissingOnes() {
    val first = image(root, "first.jpg")
    val second = image(root, "second.jpg")
    CachedImageFiles.remove(root, listOf(uri(first), uri(second), uri(File(root, "gone.jpg"))))
    assertFalse(first.exists())
    assertFalse(second.exists())
  }

  @Test
  fun oneForeignUriRejectsTheWholeCall() {
    val owned = image(root, "owned.jpg")
    val foreign = image(outside, "foreign.jpg")
    assertRejected(listOf(uri(owned), uri(foreign)))
    assertTrue(owned.exists())
    assertTrue(foreign.exists())
  }

  @Test
  fun pathTraversalOutOfTheFolderIsRejected() {
    val foreign = image(outside, "foreign.jpg")
    assertRejected(listOf("file://${root.path}/../outside/foreign.jpg"))
    assertTrue(foreign.exists())
  }

  @Test
  fun symbolicLinksAreRejected() {
    val foreign = image(outside, "foreign.jpg")
    val owned = image(root, "owned.jpg")
    val fileLink = File(root, "link.jpg").also { Os.symlink(foreign.path, it.path) }
    val ownedLink = File(root, "owned-link.jpg").also { Os.symlink(owned.path, it.path) }
    val directoryLink = File(root, "linked").also { Os.symlink(outside.path, it.path) }
    assertRejected(listOf(uri(fileLink)))
    assertRejected(listOf(uri(ownedLink)))
    assertRejected(listOf(uri(File(directoryLink, "foreign.jpg"))))
    assertTrue(foreign.exists())
    assertTrue(owned.exists())
  }

  @Test
  fun foldersAndNonFileUrisAreRejected() {
    val owned = image(root, "owned.jpg")
    val folder = File(root, "folder").apply { mkdirs() }
    assertRejected(listOf(uri(folder)))
    assertRejected(listOf(uri(root)))
    assertRejected(listOf("content://media/external/images/1"))
    assertRejected(listOf("relative/owned.jpg"))
    assertTrue(owned.exists())
    assertTrue(folder.isDirectory)
  }

  private fun assertRejected(uris: List<String>) {
    assertThrows(IllegalArgumentException::class.java) { CachedImageFiles.remove(root, uris) }
  }

  private fun image(directory: File, name: String) = File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

  private fun uri(file: File) = Uri.fromFile(file).toString()
}
