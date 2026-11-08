# Planner editor layout

The event title stays visible above **Schedule**, **Reminders** and **Details**.
Schedule holds dates, clocks, optional end time and recurrence. Reminders holds
recipients, relative or explicit day/time rules and the sound controls. Details
holds location, Copy location, notes and classification. Switching sections
retains the same draft. Browser tabs support arrows, Home and End; validation
reveals the section containing a required field.

Notes and recipes keep their main content visible and put less-used options in
one **More options** disclosure. Avoid nested disclosures and duplicate controls.
Button corners share the same control shape. Dialog actions remain reachable
when a keyboard is visible, with scrolling and **Done typing** on Android.

This applies the progressive disclosure guidance in the official GOV.UK Design
System [Details](https://design-system.service.gov.uk/components/details/) and
[Accordion](https://design-system.service.gov.uk/components/accordion/) guidance:
keep information most people need visible, make optional detail discoverable,
and avoid making users open many sections to complete a common action. The
project implements its own controls and assets; no GOV.UK code is copied.
