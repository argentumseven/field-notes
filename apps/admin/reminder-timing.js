// Calendar leads are stored explicitly; old minute-based reminders keep their timing.
const FieldReminderTiming = (() => {
  const units = {
    minutes: "Minutes",
    hours: "Hours",
    days: "Days",
    weeks: "Weeks",
    months: "Months",
    years: "Years",
  };
  const limits = {
    minutes: 5270400,
    hours: 87840,
    days: 3660,
    weeks: 522,
    months: 120,
    years: 10,
  };
  const factors = { minutes: 1, hours: 60, days: 1440, weeks: 10080 };
  function validate(lead) {
    if (
      !lead ||
      !(lead.unit in units) ||
      !Number.isInteger(lead.amount) ||
      lead.amount < 0 ||
      lead.amount > limits[lead.unit]
    )
      throw Error("Choose a reminder amount and unit, up to ten years.");
    if (typeof lead.zone !== "string" || !lead.zone || lead.zone.length > 80)
      throw Error("Choose a valid calendar timezone.");
    try {
      new Intl.DateTimeFormat("en", { timeZone: lead.zone }).format();
    } catch {
      throw Error("Choose a valid calendar timezone.");
    }
  }
  function parts(rule) {
    if (rule.lead_time)
      return { amount: rule.lead_time.amount, unit: rule.lead_time.unit };
    for (const unit of ["weeks", "days", "hours"])
      if (rule.minutes_before > 0 && rule.minutes_before % factors[unit] === 0)
        return { amount: rule.minutes_before / factors[unit], unit };
    return { amount: rule.minutes_before, unit: "minutes" };
  }
  function validateDay(day) {
    if (
      !day ||
      !Number.isInteger(day.days_before) ||
      day.days_before < 0 ||
      day.days_before > 3660 ||
      !/^([01]\d|2[0-3]):[0-5]\d$/.test(day.time || "")
    )
      throw Error("Choose a day and reminder time.");
    validate({ amount: 0, unit: "days", zone: day.zone });
  }
  function label(rule) {
    if (rule.day_time) {
      const d = rule.day_time;
      return `${d.days_before === 0 ? "Day of" : d.days_before % 7 === 0 ? d.days_before / 7 + " weeks before" : d.days_before + " days before"} at ${d.time}`;
    }

    const p = parts(rule);
    return p.amount === 0
      ? "At the start"
      : `${p.amount} ${p.amount === 1 ? p.unit.slice(0, -1) : p.unit} before`;
  }
  function apply(rule, amount, unit, zone) {
    validate({ amount, unit, zone });
    delete rule.day_time;
    const minutes = unit in factors ? amount * factors[unit] : null;
    if (amount === 0 || (minutes !== null && minutes <= 43200)) {
      rule.minutes_before = minutes || 0;
      delete rule.lead_time;
    } else {
      rule.minutes_before = 0;
      rule.lead_time = { amount, unit, zone };
    }
  }
  return { units, limits, parts, label, apply, validate, validateDay };
})();
