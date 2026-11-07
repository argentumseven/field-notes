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
  const tones = {
    chime: "Chime",
    bell: "Bell",
    pulse: "Pulse",
    beacon: "Beacon",
    marimba: "Marimba",
    orbit: "Orbit",
    sunrise: "Sunrise",
    siren: "Siren",
  };
  let activePreview = null;
  const rulesFor = (record) =>
    record?.data?.reminder_timing_rules ??
    (record?.data && "reminder_rules" in record.data
      ? record.data.reminder_rules
      : record?.data?.reminders);
  const optionsFor = (record, kind) =>
    structuredClone(
      record?.data?.reminder_options || {
        audience: { mode: "everyone", people: [] },
        playback: {},
      },
    );
  function validateOptions(value) {
    if (
      !["everyone", "people", "assigned"].includes(value.audience?.mode) ||
      !Array.isArray(value.audience.people) ||
      value.audience.people.length > 64 ||
      new Set(value.audience.people).size !== value.audience.people.length ||
      (value.audience.mode === "people" && !value.audience.people.length)
    )
      throw Error("Choose who receives reminders.");
    for (const p of Object.values(value.playback || {}))
      if (
        (p.tone && !tones[p.tone]) ||
        ![0, 5, 10, 15, 30, 60].includes(p.duration_seconds) ||
        !Number.isInteger(p.alarm_volume) ||
        p.alarm_volume < 25 ||
        p.alarm_volume > 100 ||
        (p.unmute && !p.alarm)
      )
        throw Error("Choose a supported sound, duration and alarm volume.");
  }
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
    if (rules.timing_version !== undefined && rules.timing_version !== 1)
      throw Error("Update the app for this reminder timing.");
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
      if (r.lead_time) {
        FieldReminderTiming.validate(r.lead_time);
        if (rules.timing_version !== 1 || r.minutes_before !== 0)
          throw Error("Check the reminder timing format.");
      }
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
  function controls(parent, initial, getDay, changed, extension = null) {
    const rules = structuredClone(initial || { alerts: [], repeat: null }),
      section = el("details", "reminder-editor"),
      summary = el("summary"),
      body = el("div", "reminder-body");
    const initialAudience = extension?.value.audience;
    let recipientChoice =
      initialAudience?.mode === "people"
        ? initialAudience.people.length === 1
          ? initialAudience.people[0] === state.actor.user_id
            ? "self"
            : "person:" + initialAudience.people[0]
          : "people"
        : initialAudience?.mode;
    section.append(summary, body);
    parent.append(section);
    const notify = () => {
      rules.timing_version = 1;
      summary.textContent = `Reminders · ${rules.alerts.length}${rules.repeat ? " + recurring" : ""}`;
      changed(structuredClone(rules));
      if (extension) {
        const ids = new Set([
          ...rules.alerts.map((r) => r.id),
          ...(rules.repeat ? [rules.repeat.id] : []),
        ]);
        for (const id of Object.keys(extension.value.playback))
          if (!ids.has(id)) delete extension.value.playback[id];
        extension.changed(structuredClone(extension.value));
      }
    };
    const field = (p, label, value, type = "text") =>
      addField(p, input(label, value, type));
    function profileFields(container, r, prefix) {
      const fields = el("div", "sound-options");
      const extra = extension?.value.playback[r.id] || {
        tone: r.sound,
        duration_seconds: 0,
        alarm: false,
        unmute: false,
        alarm_volume: 100,
      };
      let alarmSection = null,
        alarmControl = null,
        unmuteControl = null;
      const profileSummary = container.querySelector(":scope > summary");
      const updateSummary = () => {
        if (profileSummary)
          profileSummary.textContent = `${extra.alarm ? "Alarm" : r.importance} · ${tones[extra.tone || r.sound]} · ${extra.alarm ? (extra.duration_seconds || 30) + " seconds" : "notification"} · ${r.volume}%`;
      };
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
          Object.entries(tones).filter(
            ([key]) => extension || ["chime", "bell", "pulse"].includes(key),
          ),
          extra.tone || r.sound,
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
        r.sound = ["chime", "bell", "pulse"].includes(sound.value)
          ? sound.value
          : "chime";
        r.volume = Number(volume.value);
        r.vibrate = vib.checked;
        if (extension) {
          extra.tone = sound.value;
          if (r.importance === "quiet") {
            extra.alarm = false;
            extra.unmute = false;
            if (alarmControl) alarmControl.checked = false;
            if (unmuteControl) unmuteControl.checked = false;
          }
          extension.value.playback[r.id] = extra;
          if (alarmSection) alarmSection.hidden = r.importance === "quiet";
        }
        sound.disabled =
          volume.disabled =
          vib.disabled =
            r.importance === "quiet";
        updateSummary();
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
          activePreview?.pause();
          const audio = (activePreview = new Audio(
            `/sounds/v1/reminder_${extra.tone || r.sound}_${r.volume}.wav`,
          ));
          audio
            .play()
            .catch(() =>
              notice(
                "Sound could not play. Check this browser’s sound permissions.",
              ),
            );
        }),
      );
      fields.append(
        button("Stop preview", () => {
          activePreview?.pause();
          activePreview = null;
        }),
      );
      if (extension) {
        alarmSection = el("div", "alarm-options");
        alarmSection.hidden = r.importance === "quiet";
        const choice = (label, checked) => {
          const l = el("label", "dialog-choice"),
            n = el("input");
          n.type = "checkbox";
          n.checked = checked;
          l.append(n, document.createTextNode(label));
          alarmSection.append(l);
          return n;
        };
        const alarm = choice(prefix + " · play as an alarm", extra.alarm),
          unmute = choice("Temporarily raise alarm volume", extra.unmute);
        alarmControl = alarm;
        unmuteControl = unmute;
        const duration = addField(
          alarmSection,
          select(
            prefix + " alarm duration",
            [5, 10, 15, 30, 60].map((n) => [String(n), n + " seconds"]),
            String(extra.duration_seconds || 30),
          ),
        );
        const level = addField(
          alarmSection,
          select(
            prefix + " alarm volume",
            [25, 50, 75, 100].map((n) => [String(n), n + "% alarm volume"]),
            String(extra.alarm_volume),
          ),
        );
        const updateAlarm = () => {
          extra.alarm = alarm.checked;
          extra.unmute = alarm.checked && unmute.checked;
          extra.duration_seconds = alarm.checked ? Number(duration.value) : 0;
          extra.alarm_volume = Number(level.value);
          extension.value.playback[r.id] = extra;
          unmute.disabled = duration.disabled = level.disabled = !alarm.checked;
          updateSummary();
          notify();
        };
        alarm.onchange = () => {
          if (alarm.checked) unmute.checked = true;
          updateAlarm();
        };
        unmute.onchange = duration.onchange = level.onchange = updateAlarm;
        unmute.disabled = duration.disabled = level.disabled = !alarm.checked;
        alarmSection.append(
          el(
            "p",
            "muted",
            "Alarm mode uses the phone’s alarm audio stream. Each phone must enable temporary volume control; its previous level is restored afterward. Android’s DND and profile settings apply.",
          ),
        );
        fields.append(alarmSection);
      }
      container.append(
        fields,
        el(
          "p",
          "muted",
          "Sound level is relative to the phone’s notification volume. Android, DND and profile forwarding remain in control.",
        ),
      );
      updateSummary();
    }
    function render() {
      body.replaceChildren();
      if (extension) {
        const recipients = el("section", "reminder-recipients"),
          audience = extension.value.audience;
        const options = [
          ["self", "Only me"],
          ["everyone", "Everyone"],
          [
            "assigned",
            extension.kind === "event" ? "Event creator" : "Assigned person",
          ],
          ...state.people.map((p) => ["person:" + p.id, p.name]),
          ["people", "Choose several people"],
        ];
        const selected = recipientChoice;
        const who = addField(
          recipients,
          select("Who receives reminders", options, selected),
        );
        who.onchange = () => {
          const choice = who.value;
          recipientChoice = choice;
          extension.value.audience =
            choice === "everyone"
              ? { mode: "everyone", people: [] }
              : choice === "assigned"
                ? { mode: "assigned", people: [] }
                : {
                    mode: "people",
                    people:
                      choice === "self"
                        ? [state.actor.user_id]
                        : choice.startsWith("person:")
                          ? [choice.slice(7)]
                          : audience.mode === "people"
                            ? audience.people
                            : [state.actor.user_id],
                  };
          notify();
          render();
        };
        if (selected === "people")
          for (const person of state.people) {
            const l = el("label", "dialog-choice"),
              n = el("input");
            n.type = "checkbox";
            n.checked = audience.people.includes(person.id);
            l.append(n, document.createTextNode(person.name));
            n.onchange = () => {
              extension.value.audience.people = n.checked
                ? [...audience.people, person.id]
                : audience.people.filter((id) => id !== person.id);
              notify();
            };
            recipients.append(l);
          }
        recipients.append(
          el(
            "p",
            "muted",
            "This controls alerts. The household calendar remains shared.",
          ),
        );
        body.append(recipients);
      }
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
        const isPreset =
            !r.lead_time && options.some(([n]) => n === r.minutes_before),
          when = addField(
            head,
            select(
              `Reminder ${i + 1}`,
              options
                .map(([n, t]) => [String(n), t])
                .concat([["custom", "Custom time…"]]),
              isPreset ? String(r.minutes_before) : "custom",
            ),
          );
        const customRow = el("div", "reminder-time-controls"),
          initial = FieldReminderTiming.parts(r);
        row.append(customRow);
        const amountGroup = el("div"),
          unitGroup = el("div");
        customRow.append(amountGroup, unitGroup);
        const custom = field(
          amountGroup,
          "Amount before",
          initial.amount,
          "number",
        );
        const unit = addField(
          unitGroup,
          select(
            "Time unit",
            Object.entries(FieldReminderTiming.units),
            initial.unit,
          ),
        );
        const help = el(
          "p",
          "item-sub",
          "Months and years follow calendar dates. Other units count elapsed time.",
        );
        customRow.append(help);
        custom.min = "0";
        custom.step = "1";
        const configure = () => {
          customRow.hidden = when.value !== "custom";
          custom.required = !customRow.hidden;
          custom.max = String(FieldReminderTiming.limits[unit.value]);
          if (customRow.hidden) custom.setCustomValidity("");
        };
        const update = () => {
          configure();
          if (custom.value === "" || !custom.checkValidity()) return;
          FieldReminderTiming.apply(
            r,
            Number(custom.value),
            unit.value,
            r.lead_time?.zone || localZone(),
          );
          notify();
        };
        custom.oninput = update;
        unit.onchange = update;
        when.onchange = () => {
          configure();
          if (when.value === "custom") custom.focus();
          else {
            r.minutes_before = Number(when.value);
            delete r.lead_time;
            notify();
          }
        };
        configure();
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
      rulesFor(record) || {
        alerts: record.data.due
          ? [{ id: record.id, minutes_before: 0, ...profile }]
          : [],
        repeat: null,
      },
    );
    let options = optionsFor(record, record.kind);
    await formDialog(
      "Reminders",
      record.data.title,
      [],
      async () => {
        validate(settings);
        validateOptions(options);
        await mutate(
          record.kind + ".update",
          record.id,
          { reminders: settings, reminder_options: options },
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
          {
            value: options,
            kind: record.kind,
            changed: (v) => {
              options = v;
            },
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
    const r = rulesFor(record)?.repeat;
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
  root.FieldReminders = {
    validate,
    validateOptions,
    controls,
    edit,
    profile,
    windowLabel,
    rulesFor,
    optionsFor,
  };
})(globalThis);
