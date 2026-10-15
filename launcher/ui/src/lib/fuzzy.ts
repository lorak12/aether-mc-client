import Fuse from "fuse.js";

export interface SearchItem {
  id: string;
  /** Result section, e.g. "Pages", "Profiles", "Settings". */
  group: string;
  title: string;
  subtitle?: string;
  keywords?: string;
}

export interface ResultGroup<T extends SearchItem> {
  group: string;
  items: T[];
}

/** Fuzzy index over everything the command palette can find. Results keep the order of `groups`. */
export function searchIndex<T extends SearchItem>(items: T[], groups: string[]) {
  const fuse = new Fuse(items, {
    keys: [
      { name: "title", weight: 0.7 },
      { name: "subtitle", weight: 0.2 },
      { name: "keywords", weight: 0.1 },
    ],
    threshold: 0.38,
    ignoreLocation: true,
  });
  const order = (g: string) => {
    const i = groups.indexOf(g);
    return i < 0 ? groups.length : i;
  };
  return (query: string, perGroup = 5): ResultGroup<T>[] => {
    const q = query.trim();
    const found = q ? fuse.search(q).map((r) => r.item) : [];
    const map = new Map<string, T[]>();
    for (const it of found) {
      const list = map.get(it.group) ?? [];
      if (list.length < perGroup) map.set(it.group, [...list, it]);
    }
    return [...map.entries()].sort((a, b) => order(a[0]) - order(b[0])).map(([group, items]) => ({ group, items }));
  };
}
