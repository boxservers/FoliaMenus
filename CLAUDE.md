# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

FoliaMenus is a fork of [DeluxeMenus](https://github.com/HelpChat/DeluxeMenus) (an all-in-one inventory GUI menu plugin) adapted for [Folia](https://docs.papermc.org/folia/) support. The plugin allows server admins to define YAML-based menus that run commands, check requirements, and display items to players with full PlaceholderAPI integration. Plugin name and root project name in Gradle are still `DeluxeMenus` (artifact `DeluxeMenus-<version>.jar`).

## Build & Development Commands

The project uses the Gradle wrapper. Java 17+ is required (source/target compatibility is 11). Dependencies are declared in `gradle/libs.versions.toml`.

- **Build the shaded JAR** — `./gradlew shadowJar` (produces `build/libs/DeluxeMenus-<version>.jar`)
- **Build without shading** — `./gradlew build`
- **Check for dependency updates** — `./gradlew dependencyUpdates` (Ben Manes Versions plugin)
- **Clean** — `./gradlew clean`

There is no test suite configured (`src/test` does not exist). Manual testing happens by running the JAR on a Spigot/Paper/Folia server.

The `release` flag in `build.gradle.kts` controls whether `minorVersion` becomes `"Release"` (set to `true` before publishing a release) or `"DEV-<BUILD_NUMBER>"` (default). Version is `1.14.2`.

## Architecture

Entry point: `DeluxeMenus.java` extends `JavaPlugin` and orchestrates lifecycle: loads general config, hooks PlaceholderAPI (hard dependency — plugin disables itself otherwise), constructs `PersistentMetaHandler`, `MenuItemMarker`/`DupeFixer`, `EphemeralCooldownManager`, registers item hooks, loads all menus via `DeluxeMenusConfig`, registers `PlayerListener`, the `/deluxemenus` command, and the PlaceholderAPI expansion. Also wires bStats metrics and BungeeCord messaging.

### Package layout (`com.extendedclip.deluxemenus`)

- **`menu/`** — Core menu model. `Menu` (static map of loaded menus by name, owns `MenuOptions` and a `Map<Integer, TreeMap<Integer, MenuItem>>` keyed by page then slot), `MenuItem` (per-slot definition parsed from YAML, holds `MenuItemOptions` and click action handlers), `MenuHolder` (inventory holder implementing the marker pattern that prevents marked items leaving the menu). `menu/options/` holds immutable option records (`MenuOptions`, `MenuItemOptions`, `HeadType`, `LoreAppendMode`, `CustomModelDataComponent`). `menu/command/` holds `RegistrableMenuCommand`, which dynamically registers a Bukkit command per menu when `register_commands: true`.
- **`config/`** — `DeluxeMenusConfig` loads `config.yml`, recursively scans the `gui_menus/` directory for `*.yml` files, and parses each into a `Menu`. Also holds regex constants for `<delay=…>`/`<chance=…>` tags and the `%identifier_params%` PlaceholderAPI pattern. `GeneralConfig` handles top-level settings (debug level, update checks).
- **`action/`** — Click action model. `ClickAction` is a single action (type + executable + optional delay/chance); `ClickHandler` is the resolved runnable list of `ClickAction`s; `ClickActionTask` schedules delayed executions via the Bukkit/Folia scheduler; `ActionType` enumerates all action kinds (commands, messages, sound, opens menu, closes, connect, etc.).
- **`requirement/`** — A `RequirementList` evaluates N `Requirement`s against a `MenuHolder`, supports `minimum_requirements` and `stop_at_success`, and wires success/deny `ClickHandler`s. Concrete requirements live in this package (`HasPermission`, `HasMoney`, `HasItem`, `HasMeta`, `HasExp`, `HasEphemeralCooldown`, `IsNear`, `IsObject`, `StringLength`, `RegexMatches`, `InputResult`, `Javascript` — the last runs scripts via the embedded Nashorn engine). `RequirementType` is the enum used for YAML deserialization; `requirement/wrappers/ItemWrapper` normalizes item specs for the `has_item` requirement.
- **`hooks/`** — External plugin integrations. `ItemHook` is the common interface; `BaseHeadHook`/`NamedHeadHook`/`TextureHeadHook` resolve player heads; the rest gate behind `Bukkit.getPluginManager().isPluginEnabled(...)` and Class.forName checks (Vault, HeadDatabase, HeadDB, CraftEngine, ItemsAdder, Nexo, Oraxen, MMOItems, MythicLib via the `MythicMobs`/`ExecutableItems`/`ExecutableBlocks`/`Score`/`SimpleItemGenerator` providers). `SimpleCache` is implemented by hooks that benefit from a per-reload cache cleared by `DeluxeMenus.clearCaches()`.
- **`dupe/`** — Duplication prevention. `MenuItemMarker` tags every ItemStack the plugin creates with a short string ("DM" by default) using one of three `ItemMarker` implementations chosen at construction: `PDCMenuItemMarker` (PersistentDataContainer, preferred on modern versions), `NMSMenuItemMarker` (raw NBT, requires `NbtProvider` to be available — setup happens in `onLoad`), or `UnavailableMenuItemMarker` (no-op fallback). `DupeFixer` listens for inventory moves and removes marked items from non-menu inventories.
- **`cooldown/`** — `EphemeralCooldownManager` provides in-memory (non-persistent) cooldowns referenced by the `has_ephemeral_cooldown` requirement and a PlaceholderAPI expansion.
- **`persistentmeta/`** — `PersistentMetaHandler` stores arbitrary typed values (via `DataType` + `DataAction` enums) on players via PDC; backed by `MetaCommand` (`/dm meta …`).
- **`listener/`** — `PlayerListener` handles `InventoryClickEvent`, `InventoryCloseEvent`, `PlayerCommandPreprocessEvent` (intercepts `/<menu-command>` if not auto-registered), and `PlayerQuitEvent`. Uses Guava `Cache` instances for click debouncing (75ms normal, 200ms shift-click workaround for client quirks).
- **`command/`** — `DeluxeMenusCommand` (TabExecutor) routes `/dm …` to `SubCommand`s in `command/subcommand/`: `Help`, `Reload`, `Open`, `Execute`, `List`, `Refresh`, `Dump`, `Meta`.
- **`placeholder/`** — `Expansion` extends `PlaceholderExpansion` and exposes `%deluxemenus_*%` placeholders for menu state and persistent meta.
- **`nbt/`** — `NbtProvider` does the NMS handshake in `onLoad`; if unavailable, the plugin logs a warning and the `nbt_*`/`custom_model_data` options fall back gracefully.
- **`events/`** — `DeluxeMenusPreOpenMenuEvent` and `DeluxeMenusOpenMenuEvent` (callable hooks for other plugins).
- **`updatechecker/`** — Polls the CI server for newer versions.
- **`utils/`** — Helpers: `ItemUtils` (ItemStack building), `SkullUtils` (head textures), `AdventureUtils`/`Messages` (Kyori Adventure messaging via the `audiences` accessor on the plugin), `StringUtils` (placeholder parsing), `VersionHelper` (legacy-vs-modern feature gates, PDC support, inventory type validity), `DebugLevel` (HIGHEST/HIGH/MEDIUM/LOW/LOWEST), `Constants`, `PaginationUtils`, `DumpUtils`, `LocationUtils`, `SoundUtils`, `ExpUtils`, `Pair`.

### Lifecycle / flow

1. `onLoad` — `NbtProvider.isAvailable()` check; warn if NMS hook failed.
2. `onEnable` — load general config → hook PAPI → init persistent meta/marker/dupe/cooldown → register item hooks → `DeluxeMenusConfig.loadGUIMenus()` parses every YAML menu under `gui_menus/` into `Menu` objects (each registers its command if requested) → register `PlayerListener` → register `/deluxemenus` → register PlaceholderAPI expansion → wire BungeeCord + update checker + bStats.
3. `onDisable` — close Adventure audiences, cancel tasks, unload all menus (close for online players, unregister commands), clear cooldown + caches, unregister all handlers.
4. Menu open: triggered by command (`/dm open` or registered per-menu command) or chat interception. `Menu.openMenu` fires `DeluxeMenusPreOpenMenuEvent`, checks open requirements/bypass, renders items, opens the Bukkit inventory (held by `MenuHolder`).
5. Click: `PlayerListener.onClick` resolves the `MenuItem`, evaluates its `RequirementList`, runs the click handler (each `ClickAction` may be delayed via `<delay=…>` and gated by `<chance=…>`).

## Key Conventions

- All user-facing text uses Kyori Adventure `Component` via the plugin's `BukkitAudiences` (the `audiences()` accessor). Use `plugin.sms(sender, Messages.X)` or `plugin.sms(sender, component)` — never `sender.sendMessage(String)`.
- Debug logging goes through `plugin.debug(DebugLevel, java.util.logging.Level, String...)`. Stack traces via `plugin.printStacktrace(...)` are only printed at `MEDIUM` or stricter debug levels.
- Item stacks created by the plugin must be marked via `plugin.getMenuItemMarker().mark(stack)` before being placed in a menu (this is what the DupeFixer relies on).
- New external plugin integrations must implement `ItemHook` and be registered from `setUpItemHooks()` gated on `Bukkit.getPluginManager().isPluginEnabled(...)`; prefer a `Class.forName(...)` guard when the plugin name is generic.
- New requirement types must be added to `RequirementType`, have a class implementing `Requirement`, and be wired into `DeluxeMenusConfig`'s requirement parsing.
- New action types must be added to `ActionType` and handled in `ClickActionTask`/`ClickHandler`.
- All third-party libraries (ASM, Nashorn, Adventure, bStats) are relocated under `com.extendedclip.deluxemenus.libs.*` in the shaded JAR — keep that in mind when reading stack traces.
- Soft dependencies are declared in `src/main/resources/plugin.yml`. If you add a new optional integration, add it to `softdepend` there.
- The fork targets Folia; any scheduler call must consider the Folia threading model (region-threaded schedulers vs. `Bukkit.getScheduler()`).
- `plugin.yml` uses `${version}` which is substituted from the Gradle version during `processResources`.
