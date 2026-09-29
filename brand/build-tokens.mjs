// Generates launcher CSS variables from brand/tokens.json (single source of brand truth).
import { readFileSync, writeFileSync } from "node:fs";
const t = JSON.parse(readFileSync(new URL("./tokens.json", import.meta.url)));
const kebab = (s) => s.replace(/[A-Z]/g, (c) => "-" + c.toLowerCase());
let css = ":root {\n";
for (const [k, v] of Object.entries(t.color)) css += `  --c-${kebab(k)}: ${v};\n`;
for (const [k, v] of Object.entries(t.radius)) css += `  --r-${k}: ${v}px;\n`;
css += `  --space: ${t.space}px;\n  --dur-fast: ${t.motion.fastMs}ms;\n  --dur-base: ${t.motion.baseMs}ms;\n  --ease: ${t.motion.easing};\n`;
css += `  --font-ui: "${t.font.ui}", system-ui, sans-serif;\n  --font-display: "${t.font.display}", "${t.font.ui}", sans-serif;\n}\n`;
writeFileSync(new URL("../launcher/ui/src/tokens.css", import.meta.url), css);
console.log("wrote tokens.css");
