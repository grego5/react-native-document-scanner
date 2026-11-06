# 15 — Keep capture readiness platform-native

[Back to task index](../TASKS.md)

Status: Skipped by user decision (2026-10-31)

The C++ capture-gate migration is cancelled. Android retains CaptureGate, CaptureStability and synchronized FrameHistory, together with scheduling, hardware readiness and one-shot admission. Existing behavior and tests remain required by Task 16.

Task 29 implements equivalent Swift gating using shared focus snapshots and motion evidence, with equivalent ordered decision-sequence tests. Preserve four qualifying frames, unknown-motion semantics, focus-generation resets, history overflow handling, deadlines, requireFocus=false and exactly-once capture. Skipping this migration does not remove iOS capture gating or its validation.
