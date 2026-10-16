import { describe, expect, it } from "vitest";
import * as H from "./history";
import { clientSupported, compareVersions, groupOf, groupVersions, isLegacyFabric, listText, profileLabel } from "./versions";
import { searchIndex } from "./fuzzy";
import { dependencyQuestion, needsDependencyConfirm, resolveTarget } from "./install";
import { DEFAULT_SETTINGS, effective, isOverridden, setOverride, setOverrideValue } from "./overrides";
import { ago, compact } from "./format";
import type { Profile } from "../api/types";

describe("history", () => {
  it("pushes, goes back and forward, and drops the forward stack on a new push", () => {
    let h = H.start("home");
    h = H.push(h, "profiles");
    h = H.push(h, "content");
    expect(H.current(h)).toBe("content");
    h = H.back(H.back(h));
    expect(H.current(h)).toBe("home");
    expect(H.canBack(h)).toBe(false);
    h = H.forward(h);
    expect(H.current(h)).toBe("profiles");
    h = H.push(h, "news");
    expect(h.stack).toEqual(["home", "profiles", "news"]);
    expect(H.canForward(h)).toBe(false);
  });

  it("ignores navigation to the current route and clamps at the ends", () => {
    let h = H.start({ page: "home" });
    expect(H.push(h, { page: "home" })).toBe(h);
    expect(H.back(h)).toBe(h);
    h = H.push(h, { page: "news" });
    expect(H.forward(h)).toBe(h);
  });

  it("replace swaps the current entry", () => {
    const h = H.replace(H.push(H.start("a"), "b"), "c");
    expect(h.stack).toEqual(["a", "c"]);
  });
});

describe("versions", () => {
  it("groups classic versions by major and year versions by year", () => {
    expect(groupOf("1.21.4")).toBe("1.21");
    expect(groupOf("1.21")).toBe("1.21");
    expect(groupOf("26.3")).toBe("26");
    expect(groupOf("25w14a")).toBe("25w14a");
  });

  it("compares numerically", () => {
    expect(compareVersions("1.21.10", "1.21.9")).toBeGreaterThan(0);
    expect(compareVersions("1.21", "1.21.0")).toBe(0);
  });

  it("builds groups newest first with minors newest first, skipping snapshots", () => {
    const g = groupVersions([
      { id: "1.21", kind: "release" },
      { id: "1.21.4", kind: "release" },
      { id: "1.21.1", kind: "release" },
      { id: "1.20.1", kind: "release" },
      { id: "26.3", kind: "release" },
      { id: "25w14a", kind: "snapshot" },
    ]);
    expect(g.map((x) => x.major)).toEqual(["26", "1.21", "1.20"]);
    expect(g[1].minors).toEqual(["1.21.4", "1.21.1", "1.21"]);
  });

  it("labels profiles for the launch button", () => {
    const p = (kind: string, builtinOnly = false, loader = "fabric") => ({ name: "Create Odyssey", mc_version: "1.21.4", loader, meta: { kind, builtinOnly } }) as unknown as Profile;
    expect(profileLabel(p("client"))).toBe("Kite 1.21.4 with Fabric");
    expect(profileLabel(p("client", true))).toBe("Kite 1.21.4");
    expect(profileLabel(p("client", false, "neoforge"))).toBe("Minecraft 1.21.4 with NeoForge");
    expect(profileLabel(p("modpack"))).toBe("Create Odyssey 1.21.4");
    expect(profileLabel(p("vanilla", false, "vanilla"))).toBe("Minecraft 1.21.4");
    expect(profileLabel(p("vanilla", false, "forge"))).toBe("Minecraft 1.21.4 with Forge");
  });

  it("knows where the Kite client runs", () => {
    expect(clientSupported("1.21.11", "quilt", ["1.21.11", "26.3"])).toBe(true);
    expect(clientSupported("1.21.11", "forge", ["1.21.11"])).toBe(false);
    expect(clientSupported("1.20.1", "fabric", ["1.21.11"])).toBe(false);
    expect(clientSupported("1.8.9", "fabric", ["1.8.9"])).toBe(true);
    expect(clientSupported("1.8.9", "quilt", ["1.8.9"])).toBe(false);
    expect(isLegacyFabric("1.12.2")).toBe(true);
    expect(isLegacyFabric("1.13.2")).toBe(true);
    expect(isLegacyFabric("1.14.4")).toBe(false);
    expect(isLegacyFabric("26.1")).toBe(false);
    expect(listText(["1.21.11", "26.3"])).toBe("1.21.11 and 26.3");
    expect(listText(["a", "b", "c"])).toBe("a, b and c");
  });
});

describe("fuzzy search", () => {
  const search = searchIndex(
    [
      { id: "1", group: "Profiles", title: "Sodium test" },
      { id: "2", group: "Pages", title: "Content" },
      { id: "3", group: "Settings", title: "Memory", keywords: "ram java heap" },
      { id: "4", group: "Pages", title: "Community" },
    ],
    ["Pages", "Profiles", "Settings"],
  );

  it("tolerates typos", () => {
    expect(search("sodim").flatMap((g) => g.items.map((i) => i.id))).toContain("1");
  });

  it("matches keywords and orders groups as given", () => {
    expect(search("ram")[0].items[0].id).toBe("3");
    const groups = search("co").map((g) => g.group);
    expect(groups[0]).toBe("Pages");
  });

  it("returns nothing for an empty query", () => {
    expect(search("  ")).toEqual([]);
  });
});

describe("install target and dependencies", () => {
  const profiles = [{ id: "a" }, { id: "b" }];
  it("prefers the remembered target, then the active profile, then the first", () => {
    expect(resolveTarget("b", "a", profiles)).toBe("b");
    expect(resolveTarget("gone", "a", profiles)).toBe("a");
    expect(resolveTarget(null, null, profiles)).toBe("a");
    expect(resolveTarget(null, null, [])).toBeNull();
  });

  it("asks only when dependencies are missing", () => {
    const plan = { version: "1", dependencies: [], missing: [] };
    expect(needsDependencyConfirm(plan)).toBe(false);
    const withDeps = { ...plan, dependencies: [{ project_id: "s", title: "Sodium", version: "1" }, { project_id: "f", title: "Fabric API", version: "2" }] };
    expect(needsDependencyConfirm(withDeps)).toBe(true);
    expect(dependencyQuestion("Sodium Extra", withDeps)).toBe("Sodium Extra needs Sodium and Fabric API to work. Install them too?");
  });
});

describe("overrides", () => {
  it("uses global values unless the profile overrides them", () => {
    let ov = setOverride({}, "memoryMb", true, DEFAULT_SETTINGS);
    expect(effective(DEFAULT_SETTINGS, ov).memoryMb).toBe(4096);
    ov = setOverrideValue(ov, "memoryMb", 8192);
    expect(effective(DEFAULT_SETTINGS, ov).memoryMb).toBe(8192);
    ov = setOverride(ov, "memoryMb", false, DEFAULT_SETTINGS);
    expect(isOverridden(ov, "memoryMb")).toBe(false);
    expect(effective(DEFAULT_SETTINGS, ov).memoryMb).toBe(4096);
    // The edited value is kept for when the override is turned back on.
    expect(effective(DEFAULT_SETTINGS, setOverride(ov, "memoryMb", true, DEFAULT_SETTINGS)).memoryMb).toBe(8192);
  });
});

describe("format", () => {
  it("abbreviates counts", () => {
    expect(compact(999)).toBe("999");
    expect(compact(1234)).toBe("1.2K");
    expect(compact(3_400_000)).toBe("3.4M");
    expect(compact(312_000_000)).toBe("312M");
  });
  it("formats relative times", () => {
    const now = 1_000_000_000_000;
    expect(ago(now - 10_000, now)).toBe("just now");
    expect(ago(now - 5 * 60_000, now)).toBe("5 min ago");
    expect(ago(now - 3 * 3600_000, now)).toBe("3 h ago");
    expect(ago(now - 86400_000, now)).toBe("yesterday");
  });
});

describe("sizes", () => {
  it("formats bytes", async () => {
    const { bytes } = await import("./format");
    expect(bytes(512)).toBe("512 B");
    expect(bytes(1536)).toBe("1.5 KB");
    expect(bytes(3_400_000)).toBe("3.2 MB");
    expect(bytes(5 * 1024 ** 3)).toBe("5 GB");
  });
});
