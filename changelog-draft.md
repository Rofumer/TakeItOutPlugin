## New Features

- **Search inside shulker boxes** — when sending container contents to the client, shulker boxes are now grouped by full identity (type + contents) so the client can display them correctly
- **Shulker boxes grouped by contents** — identical shulkers are now merged into a single entry when aggregating container items

## Improvements

- **Shulker fallback** — if the requested item isn't found directly in linked containers, the server will now extract a shulker box containing the most of that item
