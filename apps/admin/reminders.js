// Private scheduling data only. Sounds are bundled on this same server; link
// previews never request retailer pages, images or favicons.
(function (root) {
  const profile = {
    importance: "normal",
    sound: "chime",
    volume: 75,
    vibrate: true,
  };
  const localZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
  const minute = (s) => {
    if (!/^\d{2}:\d{2}$/.test(s || ""))
      throw Error("Choose a local reminder time.");
    const [h, m] = s.split(":").map(Number);
    if (h > 23 || m > 59) throw Error("Choose a valid reminder time.");
    return h * 60 + m;
  };
  function validate(rules) {
    if (!Array.isArray(rules.alerts) || rules.alerts.length > 8)
      throw Error("Use up to eight reminders.");
    const ids = new Set();
    const check = (p) => {
      if (
        ids.has(p.id) ||
        !p.id ||
        !["quiet", "normal", "urgent"].includes(p.importance) ||
        !["chime", "bell", "pulse"].includes(p.sound) ||
        ![25, 50, 75, 100].includes(p.volume) ||
        typeof p.vibrate !== "boolean"
      )
        throw Error("Check reminder sound and importance.");
      ids.add(p.id);
    };
    for (const r of rules.alerts) {
      check(r);
      if (
        !Number.isInteger(r.minutes_before) ||
        r.minutes_before < 0 ||
        r.minutes_before > 43200
      )
        throw Error("Choose a reminder from the start to 30 days before.");
    }
    if (rules.repeat) {
      const r = rules.repeat;
      check(r);
      minute(r.until);
      minute(r.from);
      if (
        !Number.isInteger(r.every_minutes) ||
        r.every_minutes < 1 ||
        r.every_minutes > 1440
      )
        throw Error("Choose an interval from 1 to 1440 minutes.");
      if (
        !/^\d{4}-\d{2}-\d{2}$/.test(r.starts_on) ||
        (r.ends_on && r.ends_on < r.starts_on)
      )
        throw Error("Choose the first and optional last reminder days.");
      if (
        !Array.isArray(r.weekdays) ||
        r.weekdays.some((d) => !Number.isInteger(d) || d < 1 || d > 7) ||
        new Set(r.weekdays).size !== r.weekdays.length
      )
        throw Error("Choose reminder days.");
      try {
        new Intl.DateTimeFormat("en", { timeZone: r.zone });
      } catch {
        throw Error("Choose a recognized reminder time zone.");
      }
    }
  }
  function controls(parent, initial, getDay, changed) {
    const rules = structuredClone(initial || { alerts: [], repeat: null }),
      section = el("details", "reminder-editor"),
      summary = el("summary"),
      body = el("div", "reminder-body");
    section.append(summary, body);
    parent.append(section);
    const notify = () => {
      summary.textContent = `Reminders · ${rules.alerts.length}${rules.repeat ? " + recurring" : ""}`;
      changed(structuredClone(rules));
    };
    const field = (p, label, value, type = "text") =>
      addField(p, input(label, value, type));
    function profileFields(container, r, prefix) {
      const fields = el("div", "sound-options");
      const importance = addField(
        fields,
        select(
          prefix + " importance",
          [
            ["quiet", "Quiet"],
            ["normal", "Normal"],
            ["urgent", "Urgent"],
          ],
          r.importance,
        ),
      );
      const sound = addField(
        fields,
        select(
          prefix + " sound",
          [
            ["chime", "Chime"],
            ["bell", "Bell"],
            ["pulse", "Pulse"],
          ],
          r.sound,
        ),
      );
      const volume = addField(
        fields,
        select(
          prefix + " sound level",
          [25, 50, 75, 100].map((n) => [
            String(n),
            n + "% of phone notification volume",
          ]),
          String(r.volume),
        ),
      );
      const vibLabel = el("label", "dialog-choice"),
        vib = el("input");
      vib.type = "checkbox";
      vib.checked = r.vibrate;
      vibLabel.append(vib, document.createTextNode("Vibration"));
      fields.append(vibLabel);
      const update = () => {
        r.importance = importance.value;
        r.sound = sound.value;
        r.volume = Number(volume.value);
        r.vibrate = vib.checked;
        sound.disabled =
          volume.disabled =
          vib.disabled =
            r.importance === "quiet";
        notify();
      };
      for (const n of [importance, sound, volume, vib]) n.onchange = update;
      sound.disabled =
        volume.disabled =
        vib.disabled =
          r.importance === "quiet";
      fields.append(
        button("Preview sound", () => {
          if (r.importance === "quiet") return;
          const audio = new Audio(
            `/sounds/v1/reminder_${r.sound}_${r.volume}.wav`,
          );
          audio
            .play()
            .catch(() =>
              notice(
                "Sound could not play. Check this browser’s sound permissions.",
              ),
            );
        }),
      );
      container.append(
        fields,
        el(
          "p",
          "muted",
          "Sound level is relative to the phone’s notification volume. Android, DND and profile forwarding remain in control.",
        ),
      );
    }
    function render() {
      body.replaceChildren();
      for (const [i, r] of rules.alerts.entries()) {
        const row = el("section", "reminder-row"),
          head = el("div", "toolbar");
        const options = [
          [0, "At the start"],
          [5, "5 minutes before"],
          [10, "10 minutes before"],
          [15, "15 minutes before"],
          [30, "30 minutes before"],
          [60, "1 hour before"],
          [120, "2 hours before"],
          [1440, "1 day before"],
        ];
        const isPreset = options.some(([n]) => n === r.minutes_before),
          when = addField(
            head,
            select(
              `Reminder ${i + 1}`,
              options
                .map(([n, t]) => [String(n), t])
                .concat([["custom", "Custom minutes before"]]),
              isPreset ? String(r.minutes_before) : "custom",
            ),
          );
        const custom = field(row, "Minutes before", r.minutes_before, "number");
        custom.min = "0";
        custom.max = "43200";
        custom.hidden = custom.previousElementSibling.hidden = isPreset;
        custom.oninput = () => {
          r.minutes_before = Number(custom.value);
          notify();
        };
        when.onchange = () => {
          const c = when.value === "custom";
          custom.hidden = custom.previousElementSibling.hidden = !c;
          if (c) custom.focus();
          else {
            r.minutes_before = Number(when.value);
            notify();
          }
        };
        head.append(
          button(
            "Remove",
            () => {
              rules.alerts.splice(i, 1);
              notify();
              render();
            },
            "danger",
          ),
        );
        row.prepend(head);
        const sounds = el("details");
        sounds.append(
          el("summary", "", `${r.importance} · ${r.sound} · ${r.volume}%`),
        );
        profileFields(sounds, r, `Reminder ${i + 1}`);
        row.append(sounds);
        body.append(row);
      }
      const add = button("Add reminder", () => {
        if (rules.alerts.length < 8) {
          rules.alerts.push({ id: uuid(), minutes_before: 10, ...profile });
          notify();
          render();
        }
      });
      add.disabled = rules.alerts.length >= 8;
      body.append(add);
      const label = el("label", "dialog-choice"),
        repeat = el("input");
      repeat.type = "checkbox";
      repeat.checked = !!rules.repeat;
      label.append(
        repeat,
        document.createTextNode("Repeat during a time window"),
      );
      body.append(label);
      repeat.onchange = () => {
        rules.repeat = repeat.checked
          ? {
              id: uuid(),
              weekdays: [],
              starts_on: getDay() || FieldPlanning.localDay(),
              ends_on: "",
              from: "09:00",
              until: "18:00",
              every_minutes: 60,
              zone: localZone(),
              ...profile,
            }
          : null;
        notify();
        render();
      };
      if (rules.repeat) {
        const r = rules.repeat,
          window = el("div", "reminder-window");
        body.append(
          window,
          el(
            "p",
            "muted",
            "Repeat reminders do not reserve calendar time. Equal start/stop times mean a full day. Android may delay frequent alerts while asleep.",
          ),
        );
        const start = field(window, "First reminder day", r.starts_on, "date"),
          end = field(
            window,
            "Last reminder day · optional",
            r.ends_on,
            "date",
          );
        start.oninput = () => {
          r.starts_on = start.value;
          notify();
        };
        end.oninput = () => {
          r.ends_on = end.value;
          notify();
        };
        const mode = addField(
          window,
          select(
            "Repeat days",
            [
              ["once", "Only on the first day"],
              ["weekly", "Selected days each week"],
            ],
            r.weekdays.length ? "weekly" : "once",
          ),
        );
        const days = el("div", "weekday-choices");
        window.append(days);
        function drawDays() {
          days.replaceChildren();
          if (mode.value !== "weekly") return;
          for (let d = 1; d <= 7; d++) {
            const b = button(
              ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][d - 1],
              () => {
                r.weekdays = r.weekdays.includes(d)
                  ? r.weekdays.filter((n) => n !== d)
                  : [...r.weekdays, d].sort();
                if (!r.weekdays.length) r.weekdays = [d];
                notify();
                drawDays();
              },
              r.weekdays.includes(d) ? "selected" : "",
            );
            b.setAttribute("aria-pressed", String(r.weekdays.includes(d)));
            days.append(b);
          }
        }
        mode.onchange = () => {
          r.weekdays =
            mode.value === "once"
              ? []
              : [((new Date(r.starts_on + "T12:00").getDay() + 6) % 7) + 1];
          notify();
          drawDays();
        };
        drawDays();
        const from = field(window, "From", r.from, "time"),
          until = field(window, "Stop at", r.until, "time"),
          every = field(
            window,
            "Repeat every · minutes",
            r.every_minutes,
            "number",
          );
        every.min = "1";
        every.max = "1440";
        from.oninput = () => {
          r.from = from.value;
          notify();
        };
        until.oninput = () => {
          r.until = until.value;
          notify();
        };
        every.oninput = () => {
          r.every_minutes = Number(every.value);
          notify();
        };
        window.append(el("p", "muted", r.zone.replaceAll("_", " ")));
        if (r.zone !== localZone())
          window.append(
            button("Use this device’s time zone", () => {
              r.zone = localZone();
              notify();
              render();
            }),
          );
        const sound = el("details");
        sound.append(
          el("summary", "", "Recurring reminder sound & importance"),
        );
        profileFields(sound, r, "Recurring reminder");
        window.append(sound);
      }
      if (!rules.alerts.length && !rules.repeat)
        body.append(el("p", "muted", "No reminders for this item."));
    }
    summary.textContent = `Reminders · ${rules.alerts.length}${rules.repeat ? " + recurring" : ""}`;
    render();
    return { read: () => structuredClone(rules) };
  }
  async function edit(record) {
    let settings = structuredClone(
      record.data.reminders || { alerts: [], repeat: null },
    );
    await formDialog(
      "Reminders",
      record.data.title,
      [],
      async () => {
        validate(settings);
        await mutate(
          record.kind + ".update",
          record.id,
          { reminders: settings },
          record.revision,
        );
      },
      "Save reminders",
      () =>
        controls(
          $("#dialog-body"),
          settings,
          () =>
            record.data.due ||
            record.data.planned_for ||
            FieldPlanning.localDay(),
          (v) => {
            settings = v;
          },
        ),
    );
  }
  function zoneParts(at, zone) {
    return Object.fromEntries(
      new Intl.DateTimeFormat("en-CA", {
        timeZone: zone,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hourCycle: "h23",
      })
        .formatToParts(new Date(at * 1000))
        .filter((p) => p.type !== "literal")
        .map((p) => [p.type, p.value]),
    );
  }
  function zonedEpoch(date, time, zone) {
    const target = Date.parse(date + "T" + time + ":00Z") / 1000;
    let value = target;
    for (let i = 0; i < 5; i++) {
      const p = zoneParts(value, zone),
        local =
          Date.UTC(
            Number(p.year),
            Number(p.month) - 1,
            Number(p.day),
            Number(p.hour),
            Number(p.minute),
          ) / 1000;
      if (local === target) return value;
      value += target - local;
    }
    return null;
  }
  function windowLabel(record, day) {
    const r = record.data.reminders?.repeat;
    if (!r || (record.kind === "task" && record.data.status === "done"))
      return null;
    try {
      const start = new Date(day + "T00:00").getTime() / 1000,
        end =
          new Date(FieldPlanning.shiftDay(day, 1) + "T00:00").getTime() / 1000,
        p = zoneParts(start, r.zone),
        reference = p.year + "-" + p.month + "-" + p.day;
      for (let offset = -1; offset <= 1; offset++) {
        const date = FieldPlanning.shiftDay(reference, offset),
          weekday = ((new Date(date + "T12:00Z").getUTCDay() + 6) % 7) + 1;
        if (
          date < r.starts_on ||
          (r.ends_on && date > r.ends_on) ||
          (r.weekdays.length
            ? !r.weekdays.includes(weekday)
            : date !== r.starts_on)
        )
          continue;
        const from = zonedEpoch(date, r.from, r.zone),
          until = zonedEpoch(
            FieldPlanning.shiftDay(date, r.until <= r.from ? 1 : 0),
            r.until,
            r.zone,
          );
        if (from !== null && until !== null && from < end && until > start)
          return `Every ${r.every_minutes} min · ${r.from}–${r.until} · ${r.zone.replaceAll("_", " ")}`;
      }
    } catch {}
    return null;
  }
  root.FieldReminders = { validate, controls, edit, profile, windowLabel };
})(globalThis);
