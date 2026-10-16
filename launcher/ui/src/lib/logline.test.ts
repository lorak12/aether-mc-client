import { describe, expect, it } from "vitest";
import { parseLine, plainText } from "./logline";

const text = (l: string) => parseLine(l).segs.map((s) => s.text).join("");

describe("log line parsing", () => {
  it("splits the vanilla header into time, thread and level", () => {
    const p = parseLine("[12:03:44] [Render thread/WARN]: Missing sound for event");
    expect(p.level).toBe("warn");
    expect(p.segs.find((s) => s.kind === "time")?.text).toBe("[12:03:44]");
    expect(p.segs.find((s) => s.kind === "thread")?.text).toBe("Render thread");
    expect(p.segs.find((s) => s.kind === "level")?.text).toBe("WARN");
    expect(text("[12:03:44] [Render thread/WARN]: Missing sound for event")).toBe("[12:03:44] [Render thread/WARN]: Missing sound for event");
  });

  it("keeps the fabric logger name and lets the tag decide the level", () => {
    const p = parseLine("[09:00:01] [main/INFO] (FabricLoader) Loading 42 mods, no Exception here");
    expect(p.level).toBe("info");
    expect(p.segs.find((s) => s.kind === "logger")?.text).toBe(" (FabricLoader)");
  });

  it("turns URLs into links without trailing punctuation", () => {
    const p = parseLine("See https://example.com/a_(b)/c. and (https://kite.gg/help), thanks");
    const links = p.segs.filter((s) => s.kind === "link").map((s) => s.href);
    expect(links).toEqual(["https://example.com/a_(b)/c", "https://kite.gg/help"]);
    expect(text("See https://example.com/a_(b)/c. and (https://kite.gg/help), thanks")).toBe("See https://example.com/a_(b)/c. and (https://kite.gg/help), thanks");
  });

  it("applies Minecraft colour codes and strips them from plain text", () => {
    const line = "[Chat] §aGreen §lbold§r plain §x§f§f§0§0§8§8hex";
    const p = parseLine(line);
    expect(p.segs.find((s) => s.text === "Green ")?.style?.color).toBe("#55ff55");
    expect(p.segs.find((s) => s.text === "bold")?.style).toEqual({ color: "#55ff55", bold: true });
    expect(p.segs.find((s) => s.text === " plain ")?.style).toBeUndefined();
    expect(p.segs.find((s) => s.text === "hex")?.style?.color).toBe("#ff0088");
    expect(plainText(line)).toBe("[Chat] Green bold plain hex");
  });

  it("applies ANSI colours and drops other escapes", () => {
    const p = parseLine("\x1b[31mred\x1b[0m \x1b[2Kdone \x1b[38;2;1;2;3mrgb");
    expect(p.segs.find((s) => s.text === "red")?.style?.color).toBe("#e05561");
    expect(text("\x1b[31mred\x1b[0m \x1b[2Kdone")).toBe("red done");
    expect(p.segs.find((s) => s.text === "rgb")?.style?.color).toBe("rgb(1,2,3)");
  });

  it("marks stack traces, highlights exceptions and inherits the error level", () => {
    const head = parseLine("[10:00:00] [main/ERROR]: Crash java.lang.IllegalStateException: boom");
    expect(head.level).toBe("error");
    expect(head.segs.find((s) => s.kind === "exc")?.text).toBe("java.lang.IllegalStateException");
    const at = parseLine("\tat net.minecraft.client.Main.main(Main.java:12)", head.level);
    expect(at.trace).toBe(true);
    expect(at.level).toBe("error");
    expect(parseLine("\tat foo.Bar.baz(Bar.java:1)", "info").level).toBe("info");
    expect(parseLine("Caused by: java.io.IOException: nope").level).toBe("error");
  });
});
