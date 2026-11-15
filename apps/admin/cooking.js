/* Original, free-form cooking workflow. Durations are elapsed minutes; serving
   dates inherit the event's recurrence and wall clock, never fixed UTC days. */
(function (root) {
  const profile = () => ({
    importance: "normal",
    sound: "signal",
    volume: 100,
    vibrate: true,
    duration: 0,
    alarm: false,
    unmute: false,
    alarmVolume: 100,
    notificationUnmute: false,
    notificationVolume: 100,
    gain: 100,
    untilDismissed: false,
  });
  function schedule(plan, occurrence) {
    const stages = plan.items.flatMap((item) =>
        item.stages.map((stage) => ({ item, stage })),
      ),
      ids = new Set(stages.map((t) => t.stage.id)),
      order = [];
    if (ids.size !== stages.length || stages.length > 64)
      throw Error("Use distinct phases, up to 64 per plan.");
    while (order.length < stages.length) {
      const ready = stages.filter(
        (t) =>
          !order.includes(t) &&
          t.stage.after.every(
            (id) => ids.has(id) && order.some((v) => v.stage.id === id),
          ),
      );
      if (!ready.length)
        throw Error(
          "Dependencies must refer to existing phases and cannot form a loop.",
        );
      order.push(...ready);
    }
    const starts = new Map(),
      ends = new Map();
    for (const { stage: s } of [...order].reverse()) {
      const deadline = Math.min(
        occurrence + s.offset_minutes * 60,
        ...stages
          .filter((t) => t.stage.after.includes(s.id))
          .map((t) => starts.get(t.stage.id) - t.stage.wait_minutes * 60),
      );
      starts.set(s.id, deadline - s.duration_minutes * 60);
    }
    if ([...starts.values()].some((at) => occurrence - at > 28 * 86400))
      throw Error("The full preparation chain must fit within 28 days.");
    return order
      .map((t) => {
        const s = t.stage,
          p = plan.progress.find(
            (p) => p.occurrence === occurrence && p.stage_id === s.id,
          ),
          planned = starts.get(s.id),
          start =
            p?.state === "running"
              ? p.at
              : Math.max(
                  planned,
                  ...s.after.map((id) => ends.get(id) + s.wait_minutes * 60),
                ),
          end = p?.state === "done" ? p.at : start + s.duration_minutes * 60;
        ends.set(s.id, end);
        return { ...t, planned, start, end, progress: p };
      })
      .sort((a, b) => a.start - b.start);
  }
  const api = {
    profile,
    schedule,
    empty: () => ({ version: 1, items: [], progress: [], profile: profile() }),
  };
  root.FieldCooking = api;
  if (typeof module !== "undefined") module.exports = api;
})(typeof window === "undefined" ? globalThis : window);
