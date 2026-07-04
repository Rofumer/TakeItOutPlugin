## Bug Fixes

- **Fixed plugin failing to enable on newer Paper builds** — the item stack encoder/decoder used for cross-server item transfer relied on a `CraftItemStack.asBukkitCopy` method signature that recent Paper builds changed; the plugin now detects the correct signature automatically and no longer crashes on startup
