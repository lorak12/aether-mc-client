/** Turns one raw game-log line into styled pieces for the live log window. Pure, so it is unit tested. */

export type Level = "info" | "warn" | "error" | "debug";

export type Style = { color?: string; bold?: boolean; italic?: boolean; underline?: boolean; strike?: boolean };

/** `kind` marks pieces the window colours by role; `href` makes a piece a web link. */
export type Seg = { text: string; kind?: "time" | "thread" | "level" | "logger" | "exc" | "link"; href?: string; style?: Style };

export type Parsed = { level: Level; trace: boolean; segs: Seg[] };

// Minecraft's § palette, with black/dark blue lifted so they stay readable on the dark log background.
const MC: Record<string, string> = {
  "0": "#6b7280", "1": "#5c6bff", "2": "#00aa00", "3": "#00aaaa", "4": "#d23c3c", "5": "#aa00aa", "6": "#ffaa00", "7": "#aaaaaa",
  "8": "#7a7a7a", "9": "#5555ff", a: "#55ff55", b: "#55ffff", c: "#ff5555", d: "#ff55ff", e: "#ffff55", f: "#ffffff",
};
const ANSI = ["#6b7280", "#e05561", "#8cc265", "#d18f52", "#4aa5f0", "#c162de", "#42b3c2", "#d7dae0"];
const ANSI_BRIGHT = ["#8b93a3", "#ff616e", "#a5e075", "#f0a45d", "#4dc4ff", "#de73ff", "#4cd1e0", "#ffffff"];

const ESC = /\x1b\[[0-9;?]*[A-Za-z]/g;
const STYLE_CODE = /\x1b\[([0-9;]*)m|§x((?:§[0-9a-fA-F]){6})|§([0-9a-fk-orA-FK-OR])/g;
const HEADER = /^(\[\d{1,2}:\d{2}:\d{2}(?:[.,]\d+)?\])( \[)([^\]]*?)\/(INFO|WARN|WARNING|ERROR|FATAL|DEBUG|TRACE)(\])((?: \([^)]*\)| \[[^\]]*\])?)(:?)/;
const TOKENS = /(https?:\/\/[^\s<>"'`]+)|\b((?:[a-zA-Z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error|Throwable))\b/g;

/** The line without colour codes, as people expect to copy or search it. */
export function plainText(line: string): string {
  return line.replace(STYLE_CODE, "").replace(ESC, "");
}

function levelFromText(l: string): Level {
  if (/\b(ERROR|FATAL|Exception|Caused by)\b|\/ERROR\]/.test(l)) return "error";
  if (/\bWARN(ING)?\b|\/WARN\]/.test(l)) return "warn";
  return "info";
}

function levelFromTag(tag: string): Level {
  if (tag === "ERROR" || tag === "FATAL") return "error";
  if (tag === "WARN" || tag === "WARNING") return "warn";
  if (tag === "DEBUG" || tag === "TRACE") return "debug";
  return "info";
}

/** Trailing punctuation is usually sentence text, not part of the link; a ')' stays only when the URL opened one. */
function trimUrl(url: string): string {
  let u = url.replace(/[.,;:!?'"]+$/, "");
  while (u.endsWith(")") && (u.match(/\(/g)?.length ?? 0) < (u.match(/\)/g)?.length ?? 0)) u = u.slice(0, -1).replace(/[.,;:!?]+$/, "");
  return u;
}

function applySgr(params: string, s: Style): Style {
  const n = params === "" ? [0] : params.split(";").map(Number);
  let out: Style = { ...s };
  for (let i = 0; i < n.length; i++) {
    const c = n[i];
    if (c === 0) out = {};
    else if (c === 1) out.bold = true;
    else if (c === 3) out.italic = true;
    else if (c === 4) out.underline = true;
    else if (c === 9) out.strike = true;
    else if (c === 22) out.bold = false;
    else if (c === 23) out.italic = false;
    else if (c === 24) out.underline = false;
    else if (c === 29) out.strike = false;
    else if (c >= 30 && c <= 37) out.color = ANSI[c - 30];
    else if (c >= 90 && c <= 97) out.color = ANSI_BRIGHT[c - 90];
    else if (c === 39) delete out.color;
    else if (c === 38 && n[i + 1] === 2 && i + 4 < n.length) {
      out.color = `rgb(${n[i + 2]},${n[i + 3]},${n[i + 4]})`;
      i += 4;
    } else if (c === 38 && n[i + 1] === 5 && i + 2 < n.length) {
      const k = n[i + 2];
      if (k < 8) out.color = ANSI[k];
      else if (k < 16) out.color = ANSI_BRIGHT[k - 8];
      i += 2;
    } else if (c === 48 && (n[i + 1] === 2 || n[i + 1] === 5)) i += n[i + 1] === 2 ? 4 : 2; // backgrounds are ignored
  }
  return out;
}

function applyMc(code: string, s: Style): Style {
  const c = code.toLowerCase();
  if (MC[c]) return { color: MC[c] }; // a colour code resets formatting, as in game
  if (c === "r") return {};
  if (c === "l") return { ...s, bold: true };
  if (c === "o") return { ...s, italic: true };
  if (c === "n") return { ...s, underline: true };
  if (c === "m") return { ...s, strike: true };
  return s; // §k (obfuscated) is shown as plain text
}

/** Split styled text further into links and exception class names. */
function tokenize(text: string, style: Style | undefined, out: Seg[]) {
  let last = 0;
  for (const m of text.matchAll(TOKENS)) {
    const at = m.index ?? 0;
    const raw = m[0];
    const value = m[1] ? trimUrl(raw) : raw;
    if (at > last) out.push({ text: text.slice(last, at), style });
    out.push(m[1] ? { text: value, kind: "link", href: value, style } : { text: value, kind: "exc", style });
    last = at + value.length;
  }
  if (last < text.length) out.push({ text: text.slice(last), style });
}

function body(text: string, out: Seg[]) {
  let style: Style = {};
  let last = 0;
  const emit = (end: number) => {
    if (end > last) tokenize(text.slice(last, end).replace(ESC, ""), Object.keys(style).length ? style : undefined, out);
  };
  for (const m of text.matchAll(STYLE_CODE)) {
    const at = m.index ?? 0;
    emit(at);
    if (m[1] !== undefined) style = applySgr(m[1], style);
    else if (m[2]) style = { color: "#" + m[2].replace(/§/g, "") };
    else if (m[3]) style = applyMc(m[3], style);
    last = at + m[0].length;
  }
  emit(text.length);
}

/** `prev` is the level of the line before: stack-trace lines carry no tag of their own, so they inherit it. */
export function parseLine(raw: string, prev?: Level): Parsed {
  const segs: Seg[] = [];
  const plain = plainText(raw);
  const trace = /^\s+at\s|^\s*\.\.\. \d+ more|^\s*Caused by:|^\s*Suppressed:/.test(plain);
  let level: Level = levelFromText(plain);
  let rest = raw;
  const h = HEADER.exec(raw);
  if (h) {
    level = levelFromTag(h[4]); // the tag wins: an INFO line that merely names an exception stays info
    segs.push({ text: h[1], kind: "time" }, { text: h[2] }, { text: h[3], kind: "thread" }, { text: "/" }, { text: h[4], kind: "level" }, { text: h[5] });
    if (h[6]) segs.push({ text: h[6], kind: "logger" });
    if (h[7]) segs.push({ text: h[7] });
    rest = raw.slice(h[0].length);
  } else if (trace && prev && prev !== "info" && prev !== "debug") level = prev;
  else if (/^\s*Caused by:/.test(plain)) level = "error";
  body(rest, segs);
  return { level, trace, segs };
}
