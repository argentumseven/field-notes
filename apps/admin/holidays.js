// Original offline US federal holiday rules; no public calendar request.
const FieldHolidays = (() => {
  const cache = new Map();
  function year(year) {
    if (cache.has(year)) return cache.get(year);
    const days = new Map();
    const key = (d) =>
      `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    const add = (date, name) =>
      days.set(key(date), [...(days.get(key(date)) || []), name]);
    for (let y = year - 1; y <= year + 1; y++) {
      const fixed = [
        [0, 1, "New Year’s Day"],
        [5, 19, "Juneteenth National Independence Day"],
        [6, 4, "Independence Day"],
        [10, 11, "Veterans Day"],
        [11, 25, "Christmas Day"],
      ];
      for (const [m, d, name] of fixed) {
        if (m === 5 && y < 2021) continue;
        const date = new Date(y, m, d);
        add(date, name);
        if (date.getDay() === 0 || date.getDay() === 6)
          add(
            new Date(y, m, d + (date.getDay() === 0 ? 1 : -1)),
            name + " (observed)",
          );
      }
      for (const [m, weekday, n, name] of [
        [0, 1, 3, "Martin Luther King Jr. Day"],
        [1, 1, 3, "Washington’s Birthday"],
        [4, 1, -1, "Memorial Day"],
        [8, 1, 1, "Labor Day"],
        [9, 1, 2, "Columbus Day"],
        [10, 4, 4, "Thanksgiving Day"],
      ]) {
        if (m === 0 && y < 1986) continue;
        const first = new Date(y, m, 1),
          last = new Date(y, m + 1, 0);
        const d =
          n < 0
            ? last.getDate() - ((last.getDay() - weekday + 7) % 7)
            : 1 + ((weekday - first.getDay() + 7) % 7) + (n - 1) * 7;
        add(new Date(y, m, d), name);
      }
    }
    const result = new Map(
      [...days].filter(([day]) => day.startsWith(year + "-")),
    );
    cache.set(year, result);
    return result;
  }
  const enabled = () =>
    localStorage.getItem("field-notes-federal-holidays") === "on";
  return {
    year,
    enabled,
    on(day) {
      return enabled() ? year(Number(day.slice(0, 4))).get(day) || [] : [];
    },
  };
})();
