package com.margelo.nitro.documentscanner.processing

/** Bounded in-memory trace; appends come from the analysis executor and never touch disk. */
internal class LiveQuadTraceRecorder(
  private val config: LiveQuadStabilizerConfig = LiveQuadStabilizerConfig(),
  private val maxObservations: Int = 2_048,
  private val maxDurationNs: Long = 60_000_000_000L,
  private val maxGyroSamples: Int = 30_000,
  private val maxFrames: Int = 4_096,
) : LiveQuadTraceObserver {
  private val lock = Any()
  private val observations = ArrayList<LiveQuadObservation>()
  private val captures = ArrayList<LiveQuadCaptureEvent>()
  private val gyro = ArrayList<LiveQuadGyroSample>()
  private val frames = ArrayList<LiveFrameSample>()
  private val gates = ArrayList<LiveCaptureGateSample>()
  private var firstArrivalNs: Long? = null
  private var full = false
  private var stopped = false
  private var framesStopped = false

  /** True once either bound is reached; later observations are ignored. */
  val isFull: Boolean get() = synchronized(lock) { full }

  override fun onObservation(observation: LiveQuadObservation) {
    synchronized(lock) {
      if (stopped || full || framesStopped) return
      val first = firstArrivalNs ?: observation.arrivalNs.also { firstArrivalNs = it }
      if (observation.arrivalNs - first >= maxDurationNs) { full = true; return }
      observations.add(observation)
      if (observations.size >= maxObservations) full = true
    }
  }

  /** Frames stop with the observation bounds, or at [maxFrames]. */
  override fun onFrame(sample: LiveFrameSample) {
    synchronized(lock) { if (!stopped && !full && !framesStopped && frames.size < maxFrames) frames.add(sample) }
  }

  /** Stop frame/observation rows while keeping pending capture events and their gyro coverage attached. */
  fun stopFrames() {
    synchronized(lock) { framesStopped = true }
  }

  override fun onCaptureGate(sample: LiveCaptureGateSample) {
    synchronized(lock) { if (!stopped) gates.add(sample) }
  }

  /** The first final gate evaluation, fired or rejected, at or after [tapSensorNs]; null if none happened. */
  fun gateSince(tapSensorNs: Long): LiveCaptureGateSample? = synchronized(lock) {
    gates.firstOrNull { it.sensorClockNs >= tapSensorNs }
  }

  /** Capture events are kept until [stop], so a shutter tap near a bound still records its analysis. */
  fun addCapture(event: LiveQuadCaptureEvent) {
    synchronized(lock) { if (!stopped) captures.add(event) }
  }

  /** Sensor thread. Kept until [stop] or [maxGyroSamples], so motion around a late shutter tap is still covered. */
  fun addGyro(sample: LiveQuadGyroSample) {
    synchronized(lock) { if (!stopped && gyro.size < maxGyroSamples) gyro.add(sample) }
  }

  /** Returns an immutable snapshot; write it afterwards on the caller's thread. */
  fun stop(): LiveQuadTrace = synchronized(lock) {
    stopped = true
    LiveQuadTrace(config, observations.toList(), captures.sortedBy { it.arrivalNs }, gyro.sortedBy { it.sensorNs },
      frames = frames.toList())
  }
}
