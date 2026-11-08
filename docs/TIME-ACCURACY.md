# Time checks

Field Notes reads the Linux host's existing time service. The peer helper writes
an atomic, bounded diagnostic once per minute after successful reconciliation.
It does not run NTP inside containers, open their outbound network, install a
second daemon, or change the host clock.

The report reads `timedatectl` and, when already available, `chronyc tracking`.
Unknown, unsynchronized, stale and clock-jump states stay visible. A chrony report
must have normal leap status and an offset no greater than one second. Report
freshness uses boot identity and host uptime, so a wrong wall clock cannot make
an old report appear fresh.

The authenticated head response includes server time. Phone and browser compare
it with the midpoint of their request, allowing for round-trip time. Differences
above 30 seconds, long requests and local clock jumps produce a visible warning.
These are sanity checks, not independent proof of a correct time source. A
compromised operating system can misreport its clock and synchronization status.
Claims and concurrent edits remain server-arbitrated, independent of phone time.

In **Management → Server time**, choose **Check time again**. The phone shows
clock warnings with connection status. On the Debian host, inspect:

```bash
timedatectl status
systemctl --no-pager status systemd-timesyncd chrony
```

One unit may be absent; use the service already selected for that VM. For its
specific details, use `timedatectl timesync-status` or `chronyc tracking`. Fix the
host's time source rather than changing appointment times to compensate. The
containers retain their blocked outbound access.
