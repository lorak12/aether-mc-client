import { describe, expect, it } from "vitest";
import { cleanHtml, renderMarkdown } from "./markdown";

describe("project description markdown", () => {
  it("renders headings, lists, tables and linked badges", () => {
    const out = renderMarkdown(
      "## Features\n\n- **Fast**\n- *Small*\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n[![Fabric](https://x/fabric.svg)](https://modrinth.com/mod/sodium)",
    );
    expect(out).toContain("<h2>Features</h2>");
    expect(out).toContain("<li><strong>Fast</strong></li>");
    expect(out).toContain("<td>1</td>");
    expect(out).toContain('<a href="https://modrinth.com/mod/sodium"><img src="https://x/fabric.svg" alt="Fabric" loading="lazy"></a>');
    expect(out).not.toContain("![");
  });
  it("keeps Modrinth's inline HTML layout", () => {
    const out = renderMarkdown('<p align="center"><img src="https://x/logo.png" width="200"></p>\n\n<details><summary>More</summary>\n\nHidden\n\n</details>');
    expect(out).toContain('<p align="center">');
    expect(out).toContain('width="200"');
    expect(out).toContain("<summary>More</summary>");
  });
  it("strips scripts, frames, styles, unsafe links and non-https images", () => {
    const out = renderMarkdown(
      '<script>alert(1)</script><iframe src="https://evil"></iframe><img src="http://x/y.png" onerror="alert(1)"><a href="javascript:alert(1)">x</a><p style="color:red" onclick="x()">t</p>',
    );
    expect(out).not.toMatch(/script|iframe|onerror|javascript:|style=|onclick|<img/);
    expect(out).toContain("<a>x</a>");
  });
  it("cleans CurseForge HTML: no inline styles, unwrapped linkouts, protocol-relative images", () => {
    const out = cleanHtml(
      '<p><span style="font-size:24px">Big</span></p><a href="https://www.curseforge.com/linkout?remoteUrl=https%253a%252f%252fdiscord.gg%252fabc">d</a><img src="//media.forgecdn.net/a.png">',
    );
    expect(out).toContain("<span>Big</span>");
    expect(out).toContain('href="https://discord.gg/abc"');
    expect(out).toContain('src="https://media.forgecdn.net/a.png"');
  });
});
