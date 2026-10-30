package com.margelo.nitro.documentscanner

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * Benchmarks, timings and scene recordings run only when asked with `-e measure true`, so a normal device run checks
 * behaviour alone.
 */
internal fun assumeMeasuring() = assumeTrue(
  "A measurement; pass -e measure true to run it",
  InstrumentationRegistry.getArguments().getString("measure") == "true",
)
