# Phone lists and scrolling

Lists, list contents, tasks and project pages use one scrolling body. Headings,
filters, notices and list actions move with the items; navigation and a compact
connection/queued-change status stay in the toolbar. A visible scrollbar shows
that more content is available.

The default list view keeps search and Store easy to reach. **List options**
contains Show (unchecked/all/checked), category, sort, list settings, history,
Reuse checked and Delete checked. Cleanup still respects the selected store,
search and category, including matching checked items hidden by the Show filter.

Tap an item's card to open its details. The checkbox and outlined trash button
are separate actions. Compact rows retain quantity/store/price/category and
creator/modifier timestamps; full notes, dates and other fields remain in the
item editor. Nothing in this layout change rewrites content or history.

Item dialogs keep the list behind them. Returning from an item or another list
keeps the previous scroll and filter state. Lists stays selected while viewing
a list, and Back returns to its parent project or the list index. Returning to
a stored Store filter also restores that store as the default for new items.

Search keeps its text when the keyboard is dismissed. Swiping a list dismisses
the keyboard; programmatic scrolling and editor forms do not hide it.
