package com.margelo.nitro.documentscanner.processing

import java.io.InputStream

/** Test-only portable TSV reader. Streams belong to this reader; malformed fixtures fail, never skip. */
internal object ParityFixtures {
  fun read(input: InputStream): List<Map<String, String>> = input.bufferedReader(Charsets.UTF_8).use { reader ->
    val lines = reader.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    require(lines.size > 1) { "Empty parity fixture" }
    val header = lines.first().split('\t')
    require(header.toSet().size == header.size) { "Duplicate fixture columns" }
    lines.drop(1).map { line ->
      val fields = line.split('\t')
      require(fields.size == header.size) { "Malformed parity fixture: $line" }
      header.zip(fields).toMap()
    }
  }

  fun resource(name: String): List<Map<String, String>> =
    read(requireNotNull(javaClass.getResourceAsStream("/parity/$name.tsv")) { "Missing fixture $name" })
}
