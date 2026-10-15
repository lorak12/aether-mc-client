import { ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { api } from "../api";
import type { Installed } from "../api/types";
import { useApp } from "../app/store";
import { useSocial } from "../app/social";
import { useLayer } from "../components/hooks";
import { IArrowRight, IClock, IFolder, IPlay, IPlus, IPuzzle, ISearch, ISettings, IUser, IUserPlus, ISidebar, IPower } from "../components/Icons";
import { SkinHead } from "../components/ui";
import { searchIndex, SearchItem } from "../lib/fuzzy";
import { load } from "../lib/prefs";
import { profileLabel } from "../lib/versions";
import { SETTINGS_INDEX } from "../pages/settings/index";
import { NAV } from "./Sidebar";

interface Item extends SearchItem {
  icon: ReactNode;
  run: () => void;
}

const GROUPS = ["Pages", "Actions", "Profiles", "Settings", "Installed content", "Friends", "Recent searches"];

export function SearchOverlay() {
  const app = useApp();
  const open = app.overlay === "search";
  const close = () => app.setOverlay(null);
  useLayer(open, close);
  const social = useSocial();
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const [installed, setInstalled] = useState<Installed[]>([]);
  const input = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    setQ("");
    setSel(0);
    setTimeout(() => input.current?.focus(), 0);
    if (app.activeId) api.listInstalled(app.activeId).then(setInstalled).catch(() => setInstalled([]));
  }, [open, app.activeId]);

  const items = useMemo<Item[]>(() => {
    if (!open) return [];
    const go = (fn: () => void) => () => {
      close();
      fn();
    };
    const list: Item[] = [];
    for (const n of NAV) list.push({ id: "page-" + n.page, group: "Pages", title: n.label, icon: n.icon, run: go(() => app.navigate({ page: n.page } as never)) });
    list.push({ id: "page-coins", group: "Pages", title: "Buy coins", keywords: "currency shop wallet balance", icon: <IPlus size={18} />, run: go(() => app.navigate({ page: "coins" })) });
    list.push({ id: "page-settings", group: "Pages", title: "Settings", keywords: "preferences options", icon: <ISettings size={18} />, run: go(() => app.navigate({ page: "settings" })) });

    const actions: [string, string, ReactNode, () => void][] = [
      ["New profile", "create add instance", <IPlus size={18} />, () => app.setNewProfileOpen(true)],
      ["Add account", "sign in microsoft login", <IUser size={18} />, () => app.setOverlay("account")],
      ["Add a friend", "friend request", <IUserPlus size={18} />, () => app.navigate({ page: "community", tab: "requests" })],
      ["Open app folder", "files directory", <IFolder size={18} />, () => api.openPath("app").catch(app.error)],
      ["Open launcher logs folder", "logs files", <IFolder size={18} />, () => api.openPath("launcher-logs").catch(app.error)],
      ["Open client logs folder", "logs files game", <IFolder size={18} />, () => api.openPath("client-logs", app.activeId).catch(app.error)],
      ["Toggle sidebar", "collapse expand", <ISidebar size={18} />, () => app.setPrefs({ sidebarCollapsed: !app.prefs.sidebarCollapsed })],
      ["Restart launcher", "reload relaunch", <IPower size={18} />, () => api.restartApp().catch(() => location.reload())],
    ];
    if (app.active) actions.unshift([`Launch ${app.active.name}`, "play start game", <IPlay size={16} />, () => app.play()]);
    if (app.launch.phase === "running" || app.launch.phase === "preparing") actions.unshift(["Stop the game", "kill quit close minecraft", <IPower size={18} />, () => app.stop()]);
    actions.forEach(([title, keywords, icon, fn], i) => list.push({ id: "act-" + i, group: "Actions", title, keywords, icon, run: go(fn) }));

    for (const p of app.profiles) {
      list.push({
        id: "prof-" + p.id,
        group: "Profiles",
        title: p.name,
        subtitle: profileLabel(p),
        icon: <IPlay size={15} />,
        run: go(() => app.navigate({ page: "profiles", open: p.id })),
      });
    }
    for (const s of SETTINGS_INDEX) {
      list.push({ id: "set-" + s.id, group: "Settings", title: s.title, subtitle: s.section, keywords: s.keywords, icon: <ISettings size={18} />, run: go(() => app.navigate({ page: "settings", tab: s.tab })) });
    }
    for (const c of installed) {
      list.push({
        id: "inst-" + c.project_id,
        group: "Installed content",
        title: c.title,
        subtitle: `${c.project_type} in ${app.active?.name ?? "profile"}`,
        icon: <IPuzzle size={18} />,
        run: go(() => app.navigate({ page: "project", id: c.project_id, kind: c.project_type, provider: c.source })),
      });
    }
    for (const f of social.friends) {
      list.push({
        id: "fr-" + f.id,
        group: "Friends",
        title: f.username,
        subtitle: f.activity ?? "",
        icon: <SkinHead name={f.username} uuid={f.uuid} size={22} presence={f.presence} />,
        run: go(() => app.navigate({ page: "community", tab: "messages", friend: f.id })),
      });
    }
    for (const r of load<string[]>("recentSearches", []).slice(0, 20)) {
      list.push({ id: "rec-" + r, group: "Recent searches", title: r, icon: <IClock size={18} />, run: go(() => app.navigate({ page: "content", q: r })) });
    }
    return list;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, app.profiles, app.active, installed, social.friends, app.prefs.sidebarCollapsed]);

  const search = useMemo(() => searchIndex(items, GROUPS), [items]);
  const groups = useMemo(() => {
    if (q.trim()) return search(q);
    // Empty query: recent searches and quick actions.
    const recent = items.filter((i) => i.group === "Recent searches").slice(0, 4);
    const actions = items.filter((i) => i.group === "Actions").slice(0, 5);
    const pages = items.filter((i) => i.group === "Pages").slice(0, 7);
    return [
      ...(recent.length ? [{ group: "Recent searches", items: recent }] : []),
      { group: "Actions", items: actions },
      { group: "Pages", items: pages },
    ];
  }, [q, search, items]);
  const flat = groups.flatMap((g) => g.items);

  useEffect(() => setSel(0), [q]);
  useEffect(() => {
    listRef.current?.querySelector(`[data-i="${sel}"]`)?.scrollIntoView({ block: "nearest" });
  }, [sel]);

  if (!open) return null;
  let i = -1;
  return createPortal(
    <div className="scrim search-scrim" onMouseDown={(e) => e.target === e.currentTarget && close()}>
      <div className="palette" role="dialog" aria-label="Search">
        <label className="palette-input">
          <ISearch size={19} />
          <input
            ref={input}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search pages, profiles, settings, friends…"
            onKeyDown={(e) => {
              if (e.key === "ArrowDown") {
                e.preventDefault();
                setSel((s) => Math.min(flat.length - 1, s + 1));
              } else if (e.key === "ArrowUp") {
                e.preventDefault();
                setSel((s) => Math.max(0, s - 1));
              } else if (e.key === "Enter") {
                flat[sel]?.run();
              }
            }}
            aria-activedescendant={flat[sel] ? `pi-${flat[sel].id}` : undefined}
          />
          <kbd>Esc</kbd>
        </label>
        <div className="palette-list" ref={listRef} role="listbox">
          {flat.length === 0 && <p className="palette-empty">Nothing matches “{q}”. Try a page, profile, setting or friend's name.</p>}
          {groups.map((g) => (
            <div key={g.group} className="palette-group">
              <div className="palette-heading">{g.group}</div>
              {g.items.map((it) => {
                i++;
                const idx = i;
                return (
                  <button
                    key={it.id}
                    id={`pi-${it.id}`}
                    data-i={idx}
                    role="option"
                    aria-selected={sel === idx}
                    className={sel === idx ? "palette-item on" : "palette-item"}
                    onMouseMove={() => setSel(idx)}
                    onClick={it.run}
                  >
                    <span className="pi-icon">{it.icon}</span>
                    <span className="pi-title">{it.title}</span>
                    {it.subtitle && <span className="pi-sub">{it.subtitle}</span>}
                    {sel === idx && <IArrowRight size={15} />}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
        <footer className="palette-foot">
          <span>
            <kbd>↑</kbd>
            <kbd>↓</kbd> to move
          </span>
          <span>
            <kbd>Enter</kbd> to open
          </span>
        </footer>
      </div>
    </div>,
    document.getElementById("overlay-root") ?? document.body,
  );
}
