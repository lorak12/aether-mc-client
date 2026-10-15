import { useMemo } from "react";
import DOMPurify from "dompurify";
import { marked } from "marked";
import { api } from "../../api";

// Project descriptions: Modrinth's GitHub-flavoured Markdown (with the inline HTML it allows: headings, centred
// blocks, tables, details, badges) or CurseForge's HTML. Everything goes through DOMPurify, so a project page
// can't run script or load frames in the launcher. Images must be https; links open in the browser.

/** Protocol-relative URLs become https; CurseForge's `/linkout?remoteUrl=` wrapper is unwrapped. */
function fixUrl(u: string): string {
  if (u.startsWith("//")) u = "https:" + u;
  const out = /^https:\/\/(?:www\.)?curseforge\.com\/linkout\?remoteUrl=([^&]+)/i.exec(u);
  if (out) {
    try {
      u = decodeURIComponent(decodeURIComponent(out[1]));
    } catch {
      /* keep the wrapped link */
    }
  }
  return u;
}

const purify = DOMPurify();
purify.addHook("afterSanitizeAttributes", (node) => {
  if (node.tagName === "IMG") {
    const src = fixUrl(node.getAttribute("src") ?? "");
    if (!/^https:\/\//i.test(src)) node.remove();
    else node.setAttribute("src", src), node.setAttribute("loading", "lazy");
  }
  if (node.tagName === "A") {
    const href = fixUrl(node.getAttribute("href") ?? "");
    if (!/^https?:\/\//i.test(href)) node.removeAttribute("href");
    else node.setAttribute("href", href);
    node.removeAttribute("target");
  }
});

/** Sanitised HTML. Inline styles and classes go too, so every description uses the launcher's typography. */
export function cleanHtml(html: string): string {
  return purify.sanitize(html, {
    FORBID_TAGS: ["style", "iframe", "form", "input", "button", "textarea", "select", "object", "embed", "video", "audio", "script"],
    FORBID_ATTR: ["style", "class", "id"],
  });
}

export function renderMarkdown(source: string): string {
  return cleanHtml(marked.parse(source.replace(/\r/g, ""), { gfm: true, async: false }));
}

/** A project description: Markdown (Modrinth) or, with `html`, an HTML document (CurseForge). */
export function Markdown({ source, html: isHtml = false }: { source: string; html?: boolean }) {
  const html = useMemo(() => (!source.trim() ? "" : isHtml ? cleanHtml(source) : renderMarkdown(source)), [source, isHtml]);
  if (!html) return <div className="markdown"><p className="muted">No description.</p></div>;
  return (
    <div
      className="markdown"
      onClick={(e) => {
        const a = (e.target as HTMLElement).closest("a");
        if (!a) return;
        e.preventDefault();
        const href = a.getAttribute("href");
        if (href) api.openUrl(href).catch(() => {});
      }}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
