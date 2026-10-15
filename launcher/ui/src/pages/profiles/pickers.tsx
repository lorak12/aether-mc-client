import { useEffect, useState } from "react";
import { api } from "../../api";
import type { Loader, ProfileKind } from "../../api/types";
import { groupOf, groupVersions, LOADER_NAMES, VersionGroup } from "../../lib/versions";
import { Skeleton } from "../../components/ui";

let cache: VersionGroup[] | null = null;
/** Minecraft releases grouped by major (cached for the session). */
export function useVersionGroups() {
  const [groups, setGroups] = useState<VersionGroup[] | null>(cache);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (cache) return;
    api
      .mcVersions()
      .then((v) => {
        cache = groupVersions(v);
        setGroups(cache);
      })
      .catch((e) => setError(String(e)));
  }, []);
  return { groups, error };
}

/** Pick an exact version within its major: "1.21" offers 1.21, 1.21.1 … */
export function MinorPicker({ value, onChange, disabled }: { value: string; onChange: (v: string) => void; disabled?: boolean }) {
  const { groups } = useVersionGroups();
  const g = groups?.find((x) => x.major === groupOf(value));
  if (!groups) return <Skeleton h={34} />;
  const minors = g?.minors ?? [value];
  return (
    <div className="chip-pick" role="radiogroup" aria-label="Game version">
      {minors.map((m) => (
        <button key={m} role="radio" aria-checked={m === value} className={m === value ? "on" : ""} disabled={disabled} onClick={() => onChange(m)}>
          {m}
        </button>
      ))}
    </div>
  );
}

/** Major + minor version selects, for creating a profile. */
export function VersionSelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { groups, error } = useVersionGroups();
  useEffect(() => {
    if (groups && !value) onChange(groups[0]?.minors[0] ?? "");
  }, [groups, value, onChange]);
  if (error) return <p className="form-error">Couldn't load Minecraft versions: {error}</p>;
  if (!groups) return <Skeleton h={38} />;
  const major = groupOf(value);
  const g = groups.find((x) => x.major === major);
  return (
    <div className="version-select">
      <select value={major} onChange={(e) => onChange(groups.find((x) => x.major === e.target.value)!.minors[0])} aria-label="Major version">
        {groups.map((x) => (
          <option key={x.major} value={x.major}>
            {x.major}
          </option>
        ))}
      </select>
      <select value={value} onChange={(e) => onChange(e.target.value)} aria-label="Exact version">
        {(g?.minors ?? []).map((m) => (
          <option key={m} value={m}>
            {m}
          </option>
        ))}
      </select>
    </div>
  );
}

export type LoaderChoice = Loader | "builtin";

/** Loaders a profile kind can use. Client profiles run Aether on Fabric or Quilt, or "Vanilla" = built-in mods only. */
export function loaderChoices(kind: ProfileKind): { id: LoaderChoice; label: string; disabled?: string }[] {
  if (kind === "client")
    return [
      { id: "builtin", label: "Vanilla" },
      { id: "fabric", label: "Fabric" },
      { id: "quilt", label: "Quilt" },
      { id: "forge", label: "Forge", disabled: "The Kite client runs on Fabric and Quilt" },
      { id: "neoforge", label: "NeoForge", disabled: "The Kite client runs on Fabric and Quilt" },
    ];
  return (Object.keys(LOADER_NAMES) as Loader[]).map((l) => ({ id: l, label: LOADER_NAMES[l] }));
}

export function LoaderPicker({ kind, value, onChange, disabled }: { kind: ProfileKind; value: LoaderChoice; onChange: (v: LoaderChoice) => void; disabled?: boolean }) {
  return (
    <div className="chip-pick" role="radiogroup" aria-label="Mod loader">
      {loaderChoices(kind).map((o) => (
        <button
          key={o.id}
          role="radio"
          aria-checked={o.id === value}
          className={o.id === value ? "on" : ""}
          disabled={disabled || !!o.disabled}
          title={o.disabled}
          onClick={() => onChange(o.id)}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
