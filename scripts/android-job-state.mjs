// Only live JobScheduler sections count. Historical stats and component caches
// can retain package names after removal and cannot be erased by an app.
export function liveJobs(dump) {
  const sections = dump.match(/^(?:Registered \d+ jobs|Pending queue|Active jobs):\r?\n(?:[ \t].*(?:\r?\n|$)|\r?\n)*/gm) || [];
  return sections.some(section => section.includes('org.fieldnotes.app/'));
}
