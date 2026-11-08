const FieldClock = (() => {
  function warning(head, start, end, elapsed) {
    if (!Number.isFinite(head.server_time_ms)) return "";
    if (
      elapsed < 0 ||
      elapsed > 10000 ||
      Math.abs(end - start - elapsed) > 2000
    )
      return "Time check interrupted. Check again with a stable connection.";
    if (Math.abs(head.server_time_ms - (start + end) / 2) > 30000 + elapsed / 2)
      return "Browser and server time differ by more than 30 seconds. Check their time settings.";
    return (
      {
        unsynchronized:
          "Server time is not synchronized. Check the Linux time service.",
        stale: "Server time verification is stale.",
        clock_jump: "The server clock changed. Check the Linux time service.",
        unknown: "Server clock synchronization could not be verified.",
      }[head.clock_health?.status] || ""
    );
  }
  function accept(head, start, end, elapsed) {
    state.clockHead = head;
    state.clockWarning = warning(head, start, end, elapsed);
    const n = document.querySelector("#clock-warning");
    if (n) {
      n.textContent = state.clockWarning;
      n.hidden = !state.clockWarning;
    }
  }
  return { warning, accept };
})();
