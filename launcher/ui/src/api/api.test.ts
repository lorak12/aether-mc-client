import { describe, expect, it } from "vitest";
import { COVERS, coverOf, newsBackground } from "../lib/art";
import type { Profile } from "./types";

const base = { id: "p", name: "P", mc_version: "1.21.4", loader: "fabric", dir: "C:\\data\\profiles\\p", meta: { cover: null } } as unknown as Profile;
const withCover = (cover: string | null) => ({ ...base, meta: { ...base.meta, cover } }) as Profile;

describe("profile covers", () => {
  it("resolves an uploaded image inside the profile folder", () => {
    // Outside Tauri the asset URL falls back to the plain path.
    expect(coverOf(withCover("file:cover-1.png"))).toBe("C:\\data\\profiles\\p\\cover-1.png");
    expect(coverOf({ ...withCover("file:c.png"), dir: "/home/u/p" } as Profile)).toBe("/home/u/p/c.png");
  });
  it("passes gradients and URLs through, and has no cover by default", () => {
    expect(coverOf(withCover(COVERS[0]))).toBe(COVERS[0]);
    expect(coverOf(withCover("https://cdn.modrinth.com/x.png"))).toBe("https://cdn.modrinth.com/x.png");
    expect(coverOf(base)).toBeNull();
  });
  it("draws news pictures over a gradient fallback", () => {
    expect(newsBackground({ image: "https://x/y.png" })).toContain('url("https://x/y.png")');
    expect(newsBackground({ art: "red" })).toBe("red");
  });
});
