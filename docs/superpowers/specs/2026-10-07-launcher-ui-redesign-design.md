# Launcher UI Redesign — Design Spec

Date: 2026-10-07
Status: Implemented (2026-10-07), see §11
Scope: `launcher/ui` (React + Vite, rendered in Tauri). UI redesign only.

## 1. Goal and scope

Rebuild the Aether launcher UI around a persistent app shell (top bar, left sidebar, optional right sidebar) and seven pages plus a currency shop. This pass is **UI-first**:

- Everything the existing Rust backend already supports stays wired to it: profiles, Modrinth search/install/update, dependency resolution, `.mrpack` import, login, launch progress, and the crash log tail.
- Everything without a backend runs on **typed mock data behind the `api` layer**: coins, store, friends, DMs, news, servers, notifications from purchases, multi-account, CurseForge, and the live log stream. Mocks share the real call shapes, so swapping in a backend later only changes `api.ts`.
- These items are **visual only** by request: Check for Updates, Licenses, and Upload logs.

### Not in this pass
- Backend endpoints (`backend/`) and new Rust commands. The exceptions are the trivial window and filesystem helpers listed in §9, which the UI needs to feel real.
- Real payments, real chat transport, and a real CurseForge client.
- The in-game UI.

### Delivery: four sub-projects, built in order
Each gets its own implementation plan.

| # | Sub-project | Depends on |
|---|---|---|
| 1 | Shell and shared components (§3, §4) | — |
| 2 | Home and Profile list, incl. profile settings popover (§5.1, §5.2) | 1 |
| 3 | Content page and project detail (§5.3) | 1, 2 (install target picker) |
| 4 | Explore, Social, News, Store, Currency shop (§5.4–§5.8) | 1 |

## 2. Architecture

### 2.1 Folder layout (`launcher/ui/src`)
```
app/            App.tsx (shell composition), router.ts (history stack), store.ts (global state)
shell/          TopBar, AppMenu, NavButtons, CoinsChip, SearchOverlay, NotificationCenter,
                AccountMenu, LiveLogsButton, WindowControls, Sidebar, RightSidebar, Banner
components/     SearchBar (category dropdown + text + sort), FilterMenu, Modal, Popover (large),
                SidePanel (right slide-in), Tabs, Toggle, Slider, Checkbox, Switch (big provider
                switch), Card, BentoGrid, EmptyState, SkinHead, Badge, Toast, Menu (with submenus)
pages/          home/, profiles/, content/, explore/, social/, news/, store/, settings/
api/            index.ts (real + mock dispatch), types.ts, mock/ (one file per domain)
styles/         tokens.css (generated, unchanged), base.css, one CSS file per shell/component area
```
The current files (`App.tsx`, `pages/*.tsx`, `styles.css`) are migrated into this layout. `Scene.tsx` (three.js backdrop) stays and is reused behind the Home launch area.

### 2.2 Navigation and history
- Use a small in-house history stack (`router.ts`) instead of a routing library. A route is `{ page, params }`, e.g. `{ page: "content", params: { kind: "mod", projectId: "sodium" } }`.
- `navigate(route)` pushes the route and drops any forward entries. `back()` and `forward()` move the cursor.
- The top bar back/forward buttons are disabled at the ends of the stack. Mouse buttons 4/5 and `Alt+←/→` also navigate.
- Overlays (search, modals, popovers, side panels) are **not** history entries. `Esc` closes the topmost one.

### 2.3 State
- Use React context and reducers in `app/store.ts`. No new state library.
- Global state:
  - `profiles` and `activeProfileId`
  - `accounts` and `activeAccountId`
  - `launch` (the existing `LaunchState`)
  - `notifications`
  - `coins`
  - `installTargetProfileId` (persisted)
  - `contentProvider` (persisted)
  - `dismissedBanners` (persisted)
- Persisted UI preferences live in `localStorage` under the `aether.ui.*` keys.

### 2.4 Data layer
- `api/index.ts` exports one `api` object.
- Each mock domain (`accounts`, `coins`, `store`, `social`, `news`, `servers`, `notifications`, `curseforge`, `logs`, `versions`) lives in `api/mock/<domain>.ts` and returns Promises with small artificial latency.
- Mocks run in Tauri builds too, so the redesigned pages work in the real app.
- The existing `devMock.ts` keeps faking `__TAURI_INTERNALS__` for plain-browser design work.

### 2.5 Styling
- Keep plain CSS and the existing generated design tokens (`tokens.css`): dark surface, glass panels, accent `#6C8CFF`, Sora for display text, Inter for UI text.
- New tokens go in the brand token source, not in hand-edited CSS: banner colors (info/success/warning/danger), sidebar widths, and top bar height.

### 2.6 New dependencies
- `fuse.js` for fuzzy search. No other new dependencies.

### 2.7 Window
- Change the default size from 1200×760 to **1360×840**, and the minimum from 980×620 to **1100×680**.
- Below a 1280px window width, the right sidebar collapses to a toggleable drawer.

## 3. App shell

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ [logo] Aether [▾ menu]  [←][→]      (drag region)     [◎ 1,250][⌕][🔔][👤 Steve ●▾][▤][–□×] │  top bar
├──────────┬───────────────────────────────────────────────────────────┬───────────────────┤
│ ⌂ Home   │                                                           │ Friends           │
│ ▤ Profiles│                    page content                          │ (on Home, Social) │
│ ⬇ Content │                                                          │                   │
│ ✦ Explore │                                                          │                   │
│ ☺ Community│                                                         │ Ad slot           │
│ ▦ News    │                                                          │                   │
│ ◈ Store   │                                                          │                   │
│          │                                                           │                   │
│   [+]    │                                                           │                   │
│   [⚙]    │                                                           │                   │
└──────────┴───────────────────────────────────────────────────────────┴───────────────────┘
```

### 3.1 Top bar (every screen, 48px, drag region except interactive items)

**Left side**
1. **Client icon and name.** "Aether" in display type.
2. **App menu.** A visible chevron button that opens a menu:
   - About Aether: modal with the version, build and credits.
   - Check for Updates: *visual only*, fake spinner, then "You're up to date".
   - Licenses: *visual only*, modal with a static list.
   - Open application folder
   - Open logs folder, with a submenu to the side: **Launcher logs** and **Client logs**.
   - Upload logs: *visual only*, fake progress, then a fake share link copied toast.
   - Support, FAQ, Privacy Policy and Terms of Service: open external URLs, with placeholder URLs in config.
   - A separator, then **Settings** and **Restart** (relaunches the app).
3. **Back / Forward** buttons, per §2.2.

**Middle:** empty space that acts as the window drag region.

**Right side** (one right-aligned group)
1. **Coins chip.** Coin icon and balance. Clicking it opens the Currency shop (§5.8).
2. **Search button.** Also opens with `Ctrl+K`. It opens the search overlay (§3.4).
3. **Notifications bell.** Shows an unread badge and opens the notification center (§3.5).
4. **Account chip.**
   - Shows a 2D skin head with a small Aether logo badge in the corner, the username, and a status dot (online/away/busy/invisible).
   - Its dropdown lists all accounts with heads. Clicking one switches to it.
   - The dropdown also has "Add Microsoft account", "Add offline account", a status picker, "Sign out" for the active account, and "Manage accounts" (a Settings deep link).
   - Skin heads come from a public avatar service URL (`https://mc-heads.net/avatar/<uuid>/32`). Offline accounts fall back to a generated Steve/Alex head.
5. **Live logs button.** Enabled only while an instance is running, with a pulse dot. It opens the live log window (§3.6).
6. **Window controls:** minimise, maximise/restore and close (the existing `WindowControls`).

### 3.2 Left sidebar (wide, ~220px)
- Shows an icon and label per page: **Home, Profiles, Content, Explore, Community, News, Store**.
- The active item has an accent pill and highlighted icon.
- Pinned to the bottom: an **add profile** button (`+` icon only, opens the New Profile modal) and a **settings** button (gear icon only).
- The sidebar can collapse to icon-only (about 72px) with a toggle. The choice is persisted.

### 3.3 Right sidebar (Home and Social only)
- **Friends**:
  - A header with an online count and an add-friend button.
  - Online friends first, each with a head, name, status, and the activity line "Playing Aether 1.21 on Hypixel". Offline friends sit below in a collapsed group.
  - Hovering a friend reveals Message and Join buttons.
  - **Empty states:** "No friends yet — add someone by username" with a button, and "Nobody's online right now".
  - On Social, clicking a friend opens their DM thread.
- **Ad slot** (Home only): a fixed-height card at the bottom with an empty state showing the semi-transparent Aether logo and "Your ad here" in muted text.
- About 280px wide. Collapses to a drawer below a 1280px window width (§2.7).

### 3.4 Search overlay (`Ctrl+K` or the search button)
- A centred command-palette panel over a dimmed, blurred launcher.
- Fuzzy-searches across these sources:
  - Pages
  - Profiles
  - Settings entries (every setting has a searchable label and a deep link)
  - Installed content
  - Friends
  - Menu actions ("Open logs folder", "Add account")
  - The 20 most recent Content searches
- Results are grouped by source. Arrow keys move, Enter activates, and `Esc` closes.
- With an empty query, it shows recent items and quick actions.

### 3.5 Notification center
- A popover from the bell. Each item has an icon, a title, a detail line, a relative time and an optional action:

  | Type | Example detail | Action |
  |---|---|---|
  | Download / install | content name, target profile, progress or done | Open profile |
  | Game launched | — | — |
  | Game crashed | exit code | View log |
  | Coin purchase | — | — |
  | Cosmetic purchase | — | — |
  | Friend request | — | Accept |

- "Mark all read" and "Clear" buttons.
- Live install and launch events push here as well as to toasts.
- In-progress downloads show inline progress bars.

### 3.6 Live log window
- A second Tauri window (`?view=logs`, frameless and resizable) showing the running instance's output.
- It has a level filter (info/warn/error), a search box, auto-scroll with a lock toggle, copy, and open-in-folder.
- In this pass the stream comes from a mock tailer. Real tailing of `<profile>/logs/launcher-output.log` follows in a backend task. The button and window are fully built.

### 3.7 Announcement banner (Home, top of page content)
- A thin strip (about 44–50px) with one line of text, an optional link, and a close button.
- The variant sets the color: info (accent), success (green), warning (yellow) or danger (red).
- Banners come from `api.banners()` (mock) and have IDs. Closing one stores its ID, so it doesn't reappear.

## 4. Shared components (built in sub-project 1)

| Component | Used by | Notes |
|---|---|---|
| `SearchBar` | Profiles, Content, Explore, Store | One visual container with a category dropdown on the left, a text input in the middle, and a sort/filter dropdown on the right. Optional trailing action button, e.g. "New profile". |
| `FilterMenu` | everywhere | Dropdown with single-select (sort) or multi-select (checkbox) groups. |
| `SidePanel` | Profile details, project quick view | Slides in from the right over the content area. `Esc` or clicking outside closes it. |
| `Popover` (large) | Profile settings | Near-full-size modal with a left tab rail and a scrollable body. |
| `Modal` | install, confirmations, About, new profile | Focus trapped. Enter confirms and Esc cancels. |
| `Switch` (large) | Content provider | Two-segment toggle with brand colors. |
| `Slider` | Memory | Shows markers for recommended and system-max values. |
| `BentoGrid` | Home announcements, News | 4-column grid. Items span 1×1, 2×1 or 2×2. |
| `EmptyState` | everywhere | Icon, title, line of text and optional action. |
| `SkinHead` | top bar, friends, accounts | 2D face with an optional hat layer and an optional status dot. |
| `Menu` | App menu, account menu, row overflow menus | Keyboard navigable, with side submenus. |

## 5. Pages

### 5.1 Home
Layout from top to bottom:
1. **Banner** (§3.7), if any undismissed banner exists.
2. **Greeting row**, spanning the full width with items on both ends:
   - Left: "Welcome back, **{name} ▾**". The name opens the same account switcher as the top bar.
   - Right: "● **12,483** players online", the total Aether users online, from `api.onlineCount()` (mock).
3. **Launch area**, centred over the existing `Scene` backdrop:
   - A large **Launch Game** button.
   - Below it, the active profile label:
     - Client profile: `Aether <version> with <Loader>`, e.g. "Aether 1.21.4 with Fabric". With the Vanilla loader option it reads "Aether 1.21.4".
     - Modpack profile: `<modpack name> <game version>`.
   - A small "change" link opens a quick profile picker.
   - The existing launch states stay: preparing shows a progress bar and stage label, running shows a "Running" state with a stop option once a backend supports it, and crashed shows a crash card with a log tail and a View log button.
4. **Announcements**: a bento grid of cards with image, tag, title and date. Clicking a card opens the News article.

The right sidebar (§3.3) shows friends and the ad slot.

### 5.2 Profile list

**Header:** a `SearchBar` with these parts:
- Category dropdown: All / Client versions / Vanilla versions / Modpacks.
- Text search.
- Sort dropdown: Last played, Name, Popularity, Recently created, Game version.
- A **New profile** button.

**List:** a responsive grid of cards. Each card has:
- A cover image (generated gradient plus loader art when there is no cover).
- The name.
- A version line ("1.21 · Fabric").
- A last-played time.
- A small **Play** icon button. It sets the profile as active and navigates to Home, where the user launches it. It does **not** launch directly.

Clicking the cover or name opens the **profile SidePanel**:
- The background/cover image at the top, then the name, version and description.
- **Version chooser.** Profiles are grouped by major version. "Aether 1.21" offers 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, and so on, all from one card. The list of supported minors comes from `api.versionGroups()` (mock, mapping each major to its minors).
  > **Data change:** `Profile.mc_version` keeps the exact version the user picked. A new optional `version_group` field (e.g. "1.21") ties the card to its group. For now the UI derives the group from `mc_version` when the field is absent.
- **Loader chooser:** Vanilla, Fabric, Quilt, Forge or NeoForge.
  - On a client profile, *Vanilla* means only Aether's built-in mods load, and no third-party mods from the profile's `mods/` folder.
  - The panel shows this as a one-line hint under the chooser.
  - Wiring the behaviour in the launch path is a backend task. The UI stores the choice in `Profile.loader`.
- **Settings** button (opens the popover below) and a primary **Launch** button.

**Profile settings popover** (large `Popover`) has a left tab rail with these tabs:
- **Mods, Resource packs, Shaders, Datapacks.** These share one list layout:
  - A `SearchBar` with text and filters (enabled/disabled/has update) and the actions "Add content" (deep links to Content filtered to this profile), "Update all" and "Open folder".
  - Each row has an icon, name, version, author, an enable/disable toggle, an update button when an update exists, and an overflow menu (remove, open page).
  - Enable/disable is mocked in this pass. Real files would get renamed to `.disabled`.
  - Install, remove, update and update-all use the real backend.
- **Worlds.** Shows a thumbnail, name, game mode, last played, size, and the actions open folder, back up and delete. Mock data.
- **Servers.** Shows an icon, name, address, ping and player count, with "Play on server" and remove. Mock data.
- **Gallery.** A screenshot grid with a lightbox, open-in-folder and delete. Mock data.
- **General.** Each field has an **"Override global setting"** checkbox. When unchecked, the field is disabled and shows the global value greyed out. The fields are:
  - Game directory (default `%APPDATA%\.minecraft`), with a folder picker.
  - Memory: a slider from 512 MB to the system maximum, with a typed-number input beside it.
  - Game resolution: width × height, plus a fullscreen toggle.
  - JVM arguments.
  - Garbage collector: G1GC (default), ZGC, Shenandoah, Parallel.
  - Pre-launch command, wrapper command and post-exit command.
  - Environment variables: an editable key/value table.
  - Loader and loader version (dropdown of available versions, mock).
  - **Footer:** Export (.mrpack) (visual only this pass), Duplicate, Open folder and **Delete profile**, which needs a type-to-confirm.

> **Data change:** the `Profile` type gains an optional `overrides` object. The global defaults live in Settings (§6). The UI writes `overrides` through `api.updateProfile`. Fields the Rust side doesn't know yet are stored by the UI in a sidecar via the mock layer until the backend accepts them.

### 5.3 Content
**Header:** a `SearchBar` with these parts:
- Category dropdown: Mods / Modpacks / Resource packs / Shader packs / Datapacks.
- Text search.
- Sort dropdown: Relevance, Popularity (downloads), Follows, Recently updated, Newest.
- A large **Modrinth / CurseForge** provider `Switch` to the right of the bar. The choice is persisted. Modrinth is real. CurseForge is mocked until a key or backend exists, and shows a small "preview data" badge.

**Body:** two columns.
- **List.** Each row has:
  - An icon and the name.
  - A description clamped to 2 lines.
  - Total downloads, abbreviated ("1.2M").
  - Last updated as relative time.
  - The creator, loader badges and category chips.
  - Two buttons: **View** and **Install**.
  - Paging is infinite scroll using the existing `offset` parameter.
- **Right filter sidebar.** Contains:
  - Loader checkboxes.
  - Game version (defaults to the install target's version).
  - Categories, which change with the content kind.
  - Environment (client/server).
  - "Hide installed" for the target profile.

**Install flow:**
- **Install** opens a modal:
  1. Target profile picker. It defaults to and **remembers** the last choice (`installTargetProfileId`), so later installs are one click.
  2. A version list filtered to that profile's game version and loader, newest first. Each row has a release/beta/alpha tag and a date.
  3. Confirm.
- **Shift+Install** skips the modal. It installs the newest compatible version into the remembered target, or the active profile if none is set, and shows a toast.
- **Dependencies.** If the chosen version has required dependencies not yet installed, a confirmation lists them: "Sodium Extra requires these to work: Sodium, Fabric API. Install them too?" with **Install all** and **Only this**. This also applies to Shift+Install.
- **Modpacks.** Installing a modpack does not use the profile picker. It creates a **new profile** named after the modpack, using the modpack's icon or gallery image as the cover, then shows progress in the notification center.
- Every install shows progress in notifications (§3.5).

**Project page (View)** is a full page and a history entry:
- **Hero:** icon, name, author, downloads, followers, last updated, loaders, game versions, and an Install button.
- **Tabs:**
  - Description (rendered markdown)
  - Gallery
  - Versions (table with install buttons)
  - Dependencies
  - Insights (downloads-over-time chart, version count, release cadence; mock data where the API lacks it)
- Side links: source, issues, wiki and Discord.

### 5.4 Explore (servers)
- A `SearchBar` with a category dropdown (All / Partner / My servers), text search and a sort (Players online, Ping, Name).
- A filter sidebar with game version, region and genre (Minigames, SMP, Skyblock, PvP, …).
- **Partner servers** at the top as large cards (Hypixel, MCC Island, …), each with a banner, logo, MOTD, player count, version and ping.
- **My servers** below. These are servers the user added in game, aggregated across profiles (mock).
- Every server has a **Play** button. It launches the active (or best-matching) profile and joins the server directly through a quick-join argument. In this pass, Play sets the profile active and shows a "quick join queued" toast. The join argument is a backend task.

### 5.5 Community (Social)
- Opened from the "Community" sidebar item.
- Has sub-tabs:
  - **Messages** (default): a thread list on the left and the open conversation in the middle. Each message shows a head and a time, and the composer has an emoji button and send.
  - **Requests**: incoming and outgoing friend requests with accept, decline and cancel, plus an "Add friend" input.
  - **Blocked**
- The right sidebar shows the friends list (§3.3). Clicking a friend opens their thread.
- All data is mocked. Sending a message appends locally.
- Empty states for no friends, no messages and no requests.

### 5.6 News
- A bento grid of articles. The first is featured at 2×2, then a mix of 2×1 and 1×1.
- Each card has a tag (Update, Event, Sale, Community), image, title, excerpt and date.
- A tag filter row.
- Clicking a card opens an article view (history entry) with a hero image, title, date, rendered body and a back link.
- Mock data.

### 5.7 Store
- A category tab row: **Featured, Capes, Wings, Hats, Emotes, Bundles, Other**, matching the cosmetic types in `cosmetics/`.
- **Featured** has a hero sale carousel and a bento of highlighted items.
- Category tabs show a grid of item cards with a preview image, name, rarity color, coin price and an "Owned" state.
- The item modal has a large preview, description, price, **Buy with coins** and **Equip** (if owned).
- Buying deducts mock coins, adds the item as owned, and pushes a purchase notification.
- If the user lacks coins, the modal shows "Get more coins" and links to §5.8.

### 5.8 Currency shop
- Reached from the coins chip and from Store.
- **Coin bundles:** for example 500 / 1,100 (+10%) / 2,400 (+20%) / 6,500 (+30%), each with a price and a "best value" highlight.
- Checkout is **visual only**. A confirmation modal leads to a fake success that credits mock coins and pushes a purchase notification.
- A transaction history list at the bottom (mock).

## 6. Settings page (global)
- Opened from the sidebar gear, the app menu, or search deep links.
- Tabs:
  - **General:** language, launch behaviour (keep open, minimise or close on launch), start on boot.
  - **Appearance:** sidebar collapsed, reduce motion, banner preferences.
  - **Accounts:** a list with add and remove.
  - **Java and game defaults:** the global counterparts of every override in §5.2 General.
  - **Content:** default provider and the CurseForge key status.
  - **Notifications**
  - **About**
- The existing Settings content (sign-in and config status) moves into the Accounts and About tabs.

## 7. Error handling and states
- Every data view has three states: **loading** (skeleton shimmer rows or cards), **empty** (`EmptyState`) and **error** (inline card with message and Retry).
- Real backend errors use the existing `errText` and show as toasts and error notifications.
- Mock calls can be told to fail in dev (`?mockFail=<domain>`) to exercise error states.
- Launch failures and crashes push a notification with a View log action, in addition to the Home crash card.

## 8. Testing and verification
- The UI has no unit test runner today. This pass adds **Vitest + React Testing Library** (dev dependencies only) for logic-heavy units:
  - the history stack
  - the fuzzy search index
  - version grouping
  - the Shift-install target resolution
  - the dependency-confirmation decision
  - the override/global value resolution
- `npm run build` (tsc + vite) must pass.
- Visual checks run in a plain browser through `npm run dev` with `devMock`.
- Each sub-project ends with a **manual checklist** for the real Tauri app: every top bar item, every menu entry, back/forward, keyboard shortcuts, window resize breakpoints, and launch states.

## 9. Small native helpers allowed in this pass
These Tauri commands are needed for the shell to work. Each is a few lines and has no new backend logic:
- `open_path(kind)`: opens the app, launcher-logs or client-logs folder.
- `open_url(url)`
- `restart_app()`
- `open_logs_window()`: creates the second window (§3.6).
- `system_memory_mb()`: sets the memory slider's maximum.

Anything beyond these is listed as a follow-up backend task.

## 10. Follow-up backend tasks (out of scope, recorded for later)
- Multi-account storage and switching.
- Real live log streaming.
- Applying the Vanilla loader (built-in mods only) in the launch path.
- Persisting profile overrides in Rust.
- Content enable/disable.
- CurseForge client.
- Quick-join server argument.
- Backend APIs for coins, store, purchases, friends, DMs, presence, news, banners, online count and the server list.
- `.mrpack` export.
- Real update check.
- Log upload.

## 11. Implementation notes (2026-10-07)
What was built differs from the sections above in these places:
- **More is real than planned.** Small IPC additions made these use the real launcher instead of mocks: multi-account (`list_accounts`, `switch_account`, `remove_account` over an `accounts.json` registry), Modrinth filters and sort (`search_projects`), project pages (`project_details`, `project_versions`), installing a chosen version with or without dependencies (`install_plan`, `install_content` with `version_id`/`with_deps`), per-item updates (`update_content`), modpack to profile (`install_modpack`), and real live logs (`read_game_log` tails `<profile>/logs/launcher-output.log`). Profile version and loader can now be changed (`update_profile`).
- **Overrides that apply today:** memory, JVM arguments and garbage collector are written to the profile's `memory_mb`/`jvm_args` before each launch. Game directory, resolution, launch hooks and environment variables are saved and labelled "applied at launch in a later update". Profiles created before the redesign keep their own memory/JVM args as overrides.
- **"Vanilla" on client profiles** is stored as `builtinOnly` in the UI-side profile data, and the real loader stays Fabric/Quilt so Aether still loads. Skipping third-party mods at launch is a backend follow-up.
- **Settings > General** has launch behaviour and "show friends on Home". Language and start-on-boot were left out until they do something.
- **Insights** shows real data (downloads, followers, versions, first published, releases per month from the version history) instead of downloads-over-time, which Modrinth doesn't expose.
- **Disabled content** is a UI flag. The launcher doesn't skip those files yet, and the profile settings say so.
