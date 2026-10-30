// Pure planning rules shared by browser views and independently checked against
// the Kotlin golden cases. No network, persistence, AI service or invented date.
(function (root) {
  const day = (s) =>
    /^\d{4}-\d{2}-\d{2}$/.test(s || "")
      ? Math.floor(Date.parse(s + "T00:00:00Z") / 86400000)
      : null;
  function rankTask(record, today, now, user) {
    const d = record.data,
      p = Math.max(1, Math.min(4, Number(d.priority) || 3));
    let score = [70, 45, 25, 10][p - 1];
    const reasons = [
      ["Urgent", "High", "Normal", "Low"][p - 1] + " priority +" + score,
    ];
    const due = day(d.due),
      delta = due === null ? null : due - day(today);
    const urgency =
      delta === null
        ? 0
        : delta < 0
          ? 30 + Math.min(-delta, 20)
          : delta === 0
            ? 30
            : delta === 1
              ? 22
              : delta <= 3
                ? 16
                : delta <= 7
                  ? 8
                  : delta <= 14
                    ? 3
                    : 0;
    if (urgency) {
      score += urgency;
      reasons.push((delta < 0 ? "Overdue" : "Due soon") + " +" + urgency);
    }
    const age = d.created_at
      ? Math.min(18, Math.floor(Math.max(0, now - d.created_at) / 86400 / 3))
      : 0;
    if (age) {
      score += age;
      reasons.push("Waiting time +" + age);
    }
    const planned = day(d.planned_for);
    if (planned !== null && planned <= day(today)) {
      score += 12;
      reasons.push("Planned day +12");
    }
    const estimate = Number(d.estimate_minutes) || 0,
      effort =
        estimate > 0 && estimate <= 15
          ? 6
          : estimate <= 30 && estimate > 0
            ? 3
            : estimate <= 60 && estimate > 0
              ? 1
              : 0;
    if (effort) {
      score += effort;
      reasons.push("Small next step +" + effort);
    }
    if (d.status === "in_progress" && d.claimant === user) {
      score += 12;
      reasons.push("Already started +12");
    }
    if (d.pinned) reasons.unshift("Pinned");
    return {
      score,
      reasons,
      eligible:
        d.status !== "done" &&
        !d.blocked &&
        (!d.claimant || d.claimant === user) &&
        !(
          planned !== null &&
          planned > day(today) &&
          (due === null || due > day(today)) &&
          !(d.status === "in_progress" && d.claimant === user)
        ),
    };
  }
  const localDay = (date = new Date()) =>
    [
      date.getFullYear(),
      String(date.getMonth() + 1).padStart(2, "0"),
      String(date.getDate()).padStart(2, "0"),
    ].join("-");
  const api = { rankTask, localDay, day };
  root.FieldPlanning = api;
  if (typeof module !== "undefined") module.exports = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
