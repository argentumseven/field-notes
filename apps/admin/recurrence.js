// Bounded civil-date recurrence. No network, OS calendar, or UTC-day stepping.
window.FieldRecurrence = (() => {
  const formatters = new Map();
  const dayNumber = (d) => Date.parse(d + "T12:00:00Z") / 86400000;
  const shift = (d, n) =>
    new Date(Date.parse(d + "T12:00:00Z") + n * 86400000)
      .toISOString()
      .slice(0, 10);
  const weekday = (d) => ((new Date(d + "T12:00Z").getUTCDay() + 6) % 7) + 1;
  function parts(at, zone) {
    if (!formatters.has(zone))
      formatters.set(
        zone,
        new Intl.DateTimeFormat("en-CA", {
          timeZone: zone,
          year: "numeric",
          month: "2-digit",
          day: "2-digit",
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit",
          hourCycle: "h23",
        }),
      );
    return Object.fromEntries(
      formatters
        .get(zone)
        .formatToParts(new Date(at * 1000))
        .filter((p) => p.type !== "literal")
        .map((p) => [p.type, p.value]),
    );
  }
  const civil = (p) => `${p.year}-${p.month}-${p.day}`;
  function dayStart(date, zone) {
    for (let hour = 0; hour < 24; hour++) {
      const at = instant(date, String(hour).padStart(2, "0") + ":00:00", zone);
      if (at !== null) return at;
    }
    return instant(shift(date, 1), "00:00:00", zone);
  }
  function instant(date, time, zone, original = null) {
    const target = Date.parse(date + "T" + time + "Z");
    if (!Number.isFinite(target)) return null;
    const candidates = [];
    for (const hours of [-36, -24, -12, 0, 12, 24, 36]) {
      const probe = target + hours * 3600000,
        p = parts(probe / 1000, zone),
        offset =
          Date.UTC(
            +p.year,
            +p.month - 1,
            +p.day,
            +p.hour,
            +p.minute,
            +p.second,
          ) - probe;
      const candidate = (target - offset) / 1000,
        q = parts(candidate, zone);
      if (
        civil(q) === date &&
        `${q.hour}:${q.minute}:${q.second}` === time.padEnd(8, ":00")
      )
        candidates.push(candidate);
    }
    return candidates.includes(original)
      ? original
      : candidates.length
        ? Math.min(...candidates)
        : null;
  }
  function validate(r) {
    if (
      !["daily", "weekly", "monthly", "dates"].includes(r.frequency) ||
      !Number.isInteger(r.interval) ||
      r.interval < 1 ||
      r.interval > 365
    )
      throw Error("Choose a repeat pattern and interval from 1 to 365.");
    parts(0, r.zone);
    if (
      !Array.isArray(r.weekdays) ||
      r.weekdays.length > 7 ||
      new Set(r.weekdays).size !== r.weekdays.length ||
      r.weekdays.some((d) => !Number.isInteger(d) || d < 1 || d > 7) ||
      (r.frequency === "weekly" && !r.weekdays.length)
    )
      throw Error("Choose repeat weekdays.");
    const valid = (d) =>
      /^\d{4}-\d{2}-\d{2}$/.test(d) &&
      Number.isFinite(dayNumber(d)) &&
      new Date(d + "T12:00Z").toISOString().slice(0, 10) === d;
    for (const dates of [r.dates, r.excluded])
      if (
        !Array.isArray(dates) ||
        dates.length > 128 ||
        new Set(dates).size !== dates.length ||
        dates.some((d) => !valid(d))
      )
        throw Error("Choose up to 128 distinct dates.");
    if (r.frequency === "dates" && !r.dates.length)
      throw Error("Choose at least one occurrence date.");
    if (r.until && !valid(r.until)) throw Error("Choose a last repeat day.");
  }
  function matches(r, first, date) {
    if (
      date < first ||
      (r.until && date > r.until) ||
      r.excluded.includes(date)
    )
      return false;
    const delta = Math.round(dayNumber(date) - dayNumber(first));
    return r.frequency === "daily"
      ? delta % r.interval === 0
      : r.frequency === "weekly"
        ? Math.floor((delta + (weekday(first) % 7)) / 7) % r.interval === 0 &&
          r.weekdays.includes(weekday(date))
        : r.frequency === "monthly"
          ? ((+date.slice(0, 4) - +first.slice(0, 4)) * 12 +
              (+date.slice(5, 7) - +first.slice(5, 7))) %
              r.interval ===
              0 && date.slice(8) === first.slice(8)
          : r.dates.includes(date);
  }
  function instances(
    record,
    from,
    until,
    displayZone = Intl.DateTimeFormat().resolvedOptions().timeZone,
  ) {
    const d = record.data,
      r = d.recurrence;
    if (!r) return [record];
    try {
      validate(r);
    } catch {
      return [];
    }
    const start = d.all_day ? null : parts(d.starts_at, r.zone),
      finish = d.all_day || !d.ends_at ? null : parts(d.ends_at, r.zone);
    const first = d.all_day ? d.start_date : civil(start),
      span = d.all_day
        ? Math.round(dayNumber(d.end_date) - dayNumber(first))
        : finish
          ? Math.round(dayNumber(civil(finish)) - dayNumber(first))
          : 0;
    if (span < 0 || span > 31 || dayNumber(until) - dayNumber(from) > 400)
      return [];
    const fromAt = dayStart(from, displayZone),
      untilAt = dayStart(until, displayZone);
    const rangeStart = d.all_day ? from : civil(parts(fromAt, r.zone)),
      rangeEnd = d.all_day ? until : shift(civil(parts(untilAt, r.zone)), 1);
    const result = [];
    for (
      let day = [first, shift(rangeStart, -span - 1)].sort().at(-1);
      day < rangeEnd;
      day = shift(day, 1)
    )
      if (matches(r, first, day)) {
        const copy = { ...d, occurrence_date: day };
        if (d.all_day) {
          copy.start_date = day;
          copy.end_date = shift(day, span);
        } else {
          copy.starts_at = instant(
            day,
            `${start.hour}:${start.minute}:${start.second}`,
            r.zone,
            day === first ? d.starts_at : null,
          );
          copy.ends_at = finish
            ? instant(
                shift(day, span),
                `${finish.hour}:${finish.minute}:${finish.second}`,
                r.zone,
                day === first ? d.ends_at : null,
              )
            : null;
          if (
            copy.starts_at === null ||
            (finish &&
              (copy.ends_at === null || copy.ends_at <= copy.starts_at))
          )
            continue;
        }
        if (
          d.all_day
            ? copy.start_date < until && copy.end_date > from
            : copy.starts_at < untilAt &&
              (copy.ends_at ? copy.ends_at > fromAt : copy.starts_at >= fromAt)
        )
          result.push({ ...record, data: copy });
      }
    return result;
  }
  function label(r) {
    if (!r) return "Does not repeat";
    return r.frequency === "daily"
      ? r.interval === 1
        ? "Every day"
        : r.interval === 2
          ? "Every other day"
          : `Every ${r.interval} days`
      : r.frequency === "weekly"
        ? (r.interval === 1
            ? "Weekly"
            : r.interval === 2
              ? "Alternate weeks"
              : `Every ${r.interval} weeks`) +
          " · " +
          r.weekdays
            .slice()
            .sort((a, b) => (a % 7) - (b % 7))
            .map(
              (n) => ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][n - 1],
            )
            .join(", ")
        : r.frequency === "monthly"
          ? r.interval === 12
            ? "Every year"
            : r.interval % 12 === 0
              ? `Every ${r.interval / 12} years`
              : r.interval === 1
                ? "Monthly"
                : `Every ${r.interval} months`
          : `${r.dates.length} chosen dates`;
  }
  function preset(r) {
    if (!r) return "none";
    if (r.frequency === "daily" && r.interval === 2) return "alternate";
    if (r.frequency === "weekly" && r.interval === 2) return "fortnightly";
    if (
      r.frequency === "weekly" &&
      r.interval === 1 &&
      [...r.weekdays].sort().join(",") === "1,2,3,4,5"
    )
      return "weekdays";
    if (r.frequency === "monthly" && r.interval === 12) return "yearly";
    return r.interval === 1 || r.frequency === "dates" ? r.frequency : "custom";
  }
  function nextDates(r, first, from, limit = 5) {
    validate(r);
    if (!Number.isInteger(limit) || limit < 1 || limit > 10)
      throw Error("Choose up to 10 preview dates.");
    const after = first > from ? first : from;
    if (r.until && after > r.until) return [];
    if (r.frequency === "dates")
      return [...r.dates]
        .sort()
        .filter((d) => d >= after && matches(r, first, d))
        .slice(0, limit);
    const result = [],
      take = (day) => {
        if (/^\d{4}-\d{2}-\d{2}$/.test(day) && day >= after && matches(r, first, day) && result.length < limit)
          result.push(day);
      };
    if (r.frequency === "monthly") {
      const anchor = +first.slice(0, 4) * 12 + +first.slice(5, 7) - 1;
      const offset = +after.slice(0, 4) * 12 + +after.slice(5, 7) - 1 - anchor;
      let month = anchor + Math.floor(offset / r.interval) * r.interval;
      for (
        let i = 0;
        i < 1024 && result.length < limit;
        i++, month += r.interval
      ) {
        const year = Math.floor(month / 12),
          number = (month % 12) + 1;
        if (year > 9999) break;
        const day = `${String(year).padStart(4, "0")}-${String(number).padStart(2, "0")}-${first.slice(8)}`;
        if (r.until && day > r.until) break;
        if (new Date(day + "T12:00Z").toISOString().slice(0, 10) === day)
          take(day);
      }
    } else {
      const weekly = r.frequency === "weekly",
        step = r.interval * (weekly ? 7 : 1);
      const anchor = weekly ? shift(first, -(weekday(first) % 7)) : first;
      let day = shift(
        anchor,
        Math.floor((dayNumber(after) - dayNumber(anchor)) / step) * step,
      );
      for (
        let i = 0;
        i < 256 && result.length < limit;
        i++, day = shift(day, step)
      ) {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || r.until && day > r.until) break;
        for (let n = 0; n < (weekly ? 7 : 1); n++) take(shift(day, n));
      }
    }
    return result;
  }
  function controls(parent, initial, getStart, changed) {
    let rule = initial ? structuredClone(initial) : null;
    let custom = preset(rule) === "custom";
    let customUnit =
      rule?.frequency === "monthly" && rule.interval % 12 === 0
        ? "yearly"
        : rule?.frequency || "weekly";
    const box = el("section", "repeat-editor");
    parent.append(box);
    const seed = () => ({
      frequency: "weekly",
      interval: 1,
      weekdays: [weekday(getStart())],
      dates: [getStart()],
      until: "",
      excluded: [],
      zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    });
    const notify = () => {
      changed(rule ? structuredClone(rule) : null);
      render();
    };
    const addDate = (title, key) => {
      const line = el("div", "view-filters"),
        date = input(title, getStart(), "date"),
        add = button("Add date", () => {
          if (date.node.value && !rule[key].includes(date.node.value)) {
            rule[key].push(date.node.value);
            rule[key].sort();
            notify();
          }
        });
      line.append(addField(el("div"), date).parentElement, add);
      box.append(line);
      const chips = el("div", "repeat-days");
      for (const day of rule[key]) {
        const remove = button(
          new Date(day + "T12:00").toLocaleDateString([], {
            month: "short",
            day: "numeric",
            year: "numeric",
          }) + " ×",
          () => {
            rule[key] = rule[key].filter((d) => d !== day);
            notify();
          },
        );
        remove.setAttribute(
          "aria-label",
          "Remove " + title.toLowerCase() + " " + day,
        );
        chips.append(remove);
      }
      box.append(chips);
    };
    function render() {
      box.replaceChildren(el("h3", "", "Repeating event"));
      const options = [
        ["none", "Does not repeat"],
        ["daily", "Every day"],
        ["alternate", "Every other day"],
        ["weekdays", "Weekdays"],
        ["weekly", "Weekly · choose days"],
        ["fortnightly", "Alternate weeks"],
        ["monthly", "Monthly"],
        ["yearly", "Every year"],
        ["dates", "Choose individual dates"],
        ["custom", "Custom interval"],
      ];
      const choice = select(
        "Repeat",
        options,
        custom && rule ? "custom" : preset(rule),
      );
      box.append(addField(el("div"), choice).parentElement);
      choice.node.onchange = () => {
        const key = choice.node.value;
        custom = key === "custom";
        if (key === "none") rule = null;
        else {
          rule = rule || seed();
          if (key !== "custom") {
            rule.frequency = ["alternate", "daily"].includes(key)
              ? "daily"
              : ["weekdays", "weekly", "fortnightly"].includes(key)
                ? "weekly"
                : key === "yearly"
                  ? "monthly"
                  : key;
            rule.interval =
              key === "yearly"
                ? 12
                : ["alternate", "fortnightly"].includes(key)
                  ? 2
                  : 1;
            if (key === "weekdays") rule.weekdays = [1, 2, 3, 4, 5];
            if (rule.frequency === "weekly" && !rule.weekdays.length)
              rule.weekdays = [weekday(getStart())];
          }
          customUnit =
            rule.frequency === "monthly" && rule.interval % 12 === 0
              ? "yearly"
              : rule.frequency;
        }
        notify();
      };
      if (!rule) return;
      if (custom) {
        const frequency = select(
            "Repeat every",
            [
              ["daily", "Days"],
              ["weekly", "Weeks"],
              ["monthly", "Months"],
              ["yearly", "Years"],
              ["dates", "Individual dates"],
            ],
            customUnit,
          ),
          interval = input(
            "Every",
            String(
              customUnit === "yearly" ? rule.interval / 12 : rule.interval,
            ),
            "number",
          );
        interval.node.min = 1;
        interval.node.max = customUnit === "yearly" ? 30 : 365;
        const line = el("div", "view-filters");
        line.append(
          ...(rule.frequency === "dates"
            ? []
            : [addField(el("div"), interval).parentElement]),
          addField(el("div"), frequency).parentElement,
        );
        box.append(line);
        interval.node.onchange = () => {
          rule.interval =
            Number(interval.node.value) * (customUnit === "yearly" ? 12 : 1);
          notify();
        };
        frequency.node.onchange = () => {
          customUnit = frequency.node.value;
          rule.frequency = customUnit === "yearly" ? "monthly" : customUnit;
          rule.interval = customUnit === "yearly" ? 12 : 1;
          notify();
        };
      }
      if (rule.frequency === "weekly") {
        const days = el("div", "repeat-days");
        ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].forEach((name, i) => {
          const n = i === 0 ? 7 : i,
            b = button(name, () => {
              rule.weekdays = rule.weekdays.includes(n)
                ? rule.weekdays.filter((d) => d !== n)
                : [...rule.weekdays, n].sort();
              notify();
            });
          b.setAttribute("aria-pressed", String(rule.weekdays.includes(n)));
          days.append(b);
        });
        box.append(days);
      }
      if (rule.frequency === "dates") addDate("Occurrence date", "dates");
      const end = input("Last repeat day · optional", rule.until, "date");
      end.node.required = false;
      end.node.min = getStart();
      end.node.onchange = () => {
        rule.until = end.node.value;
        notify();
      };
      box.append(addField(el("div"), end).parentElement);
      const skip = el("details"),
        summary = el(
          "summary",
          "",
          `Skip dates · ${rule.excluded.length} exceptions`,
        );
      skip.append(summary);
      box.append(skip);
      box.append(
        el(
          "p",
          "muted",
          "Repeats in " +
            rule.zone.replaceAll("_", " ") +
            ". February 29 repeats in leap years. Missing dates and nonexistent clock-change times are skipped.",
        ),
      );
      // Place the exception controls in their disclosure while retaining one renderer.
      const oldCount = box.childNodes.length;
      addDate("Skipped date", "excluded");
      for (const node of [...box.childNodes].slice(oldCount)) skip.append(node);
      try {
        const preview = nextDates(
          rule,
          getStart(),
          civil(parts(Date.now() / 1000, rule.zone)),
        ).map((d) =>
          new Date(d + "T12:00").toLocaleDateString([], {
            month: "short",
            day: "numeric",
            year: "numeric",
          }),
        );
        box.append(
          el(
            "p",
            "muted",
            label(rule) +
              " · Next: " +
              (preview.join(" · ") || "none with these repeat settings"),
          ),
        );
      } catch (e) {
        box.append(el("p", "error", e.message));
      }
    }
    render();
    return { value: () => rule };
  }
  return {
    matches,
    instances,
    instant,
    parts,
    validate,
    label,
    preset,
    nextDates,
    controls,
    shift,
  };
})();
