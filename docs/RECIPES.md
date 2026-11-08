# Recipes

Open **Library → Recipes → New recipe** on the website or phone. Enter the name,
ingredients and cooking instructions in one form. Notes remain optional. **More
options** contains classification and optional follow-up settings. Ingredients
and instructions are plain text, each bounded to 16 KiB.

Recipes are a subtype of the existing shared note. They use the same server
permissions, encrypted storage, offline queue, revision checks, version history,
checkpoints and portable exports. Search matches the title, notes, ingredients
and full instructions. Ordinary Notes remain separate; a recipe deliberately
marked for follow-up also appears in Later & reminders.

No recipe website, retailer, metadata service or image host is contacted.
Existing general notes keep their identity. Older compatible phones can still
edit their title or notes without erasing recipe fields. Editing new fields
requires a server advertising `planner-details-v1`; queued edits are retained
with an explanation if that feature is unavailable.
