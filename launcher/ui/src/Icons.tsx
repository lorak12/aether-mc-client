import { ReactNode } from "react";

const svg = (children: ReactNode) => (
  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
    {children}
  </svg>
);

export const IconHome = () => svg(<><path d="M3 11.5 12 4l9 7.5" /><path d="M5.5 10v9.5h13V10" /><path d="M10 19.5v-5h4v5" /></>);
export const IconProfiles = () => svg(<><path d="M12 3 3.5 7.5 12 12l8.5-4.5L12 3Z" /><path d="m3.5 12 8.5 4.5 8.5-4.5" /><path d="m3.5 16.5 8.5 4.5 8.5-4.5" /></>);
export const IconContent = () => svg(<><circle cx="12" cy="12" r="9" /><path d="M3 12h18" /><path d="M12 3c2.6 2.6 3.9 5.6 3.9 9s-1.3 6.4-3.9 9c-2.6-2.6-3.9-5.6-3.9-9S9.400 5.600 12 3Z" /></>);
export const IconSettings = () => svg(<><circle cx="12" cy="12" r="3" /><path d="M19.400 15a1.700 1.700 0 0 0 .3 1.800l.1.1a2 2 0 1 1-2.800 2.800l-.1-.1a1.700 1.700 0 0 0-1.800-.3 1.700 1.700 0 0 0-1 1.500V21a2 2 0 1 1-4 0v-.1a1.700 1.700 0 0 0-1.100-1.500 1.700 1.700 0 0 0-1.800.3l-.1.1a2 2 0 1 1-2.800-2.800l.1-.1a1.700 1.700 0 0 0 .3-1.800 1.700 1.700 0 0 0-1.500-1H3a2 2 0 1 1 0-4h.1a1.700 1.700 0 0 0 1.500-1.100 1.700 1.700 0 0 0-.3-1.800l-.1-.1a2 2 0 1 1 2.800-2.800l.1.1a1.700 1.700 0 0 0 1.800.3H9a1.700 1.700 0 0 0 1-1.500V3a2 2 0 1 1 4 0v.1a1.700 1.700 0 0 0 1 1.500 1.700 1.700 0 0 0 1.800-.3l.1-.1a2 2 0 1 1 2.800 2.800l-.1.1a1.700 1.700 0 0 0-.3 1.800V9a1.700 1.700 0 0 0 1.500 1H21a2 2 0 1 1 0 4h-.1a1.700 1.700 0 0 0-1.500 1Z" /></>);
export const IconPlay = () => (
  <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden>
    <path d="M7 4.500v15a1 1 0 0 0 1.500.9l12-7.500a1 1 0 0 0 0-1.700l-12-7.500A1 1 0 0 0 7 4.500Z" />
  </svg>
);

// Isometric voxel island used as the home hero art.
type Cube = { i: number; j: number; k: number; top: string; left: string; right: string };
const W = 30;
const H = 17;
const Z = 30;

function buildIsland(): Cube[] {
  const cubes: Cube[] = [];
  const grass = { top: "#5be6b0", left: "#1f8f86", right: "#186a78" };
  const dirt = { top: "#7a86c9", left: "#3b4380", right: "#2c3266" };
  const stone = { top: "#5a6290", left: "#2a3058", right: "#20244a" };
  for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) {
    cubes.push({ i, j, k: 2, ...grass });
    cubes.push({ i, j, k: 1, ...dirt });
  }
  for (let i = 1; i < 3; i++) for (let j = 1; j < 3; j++) cubes.push({ i, j, k: 0, ...stone });
  cubes.push({ i: 1, j: 2, k: -1, ...stone });
  // tree-ish pillar + crystal
  cubes.push({ i: 0, j: 0, k: 3, top: "#c9a7ff", left: "#7f4fd0", right: "#5f37a8" });
  cubes.push({ i: 0, j: 0, k: 4, top: "#e2ccff", left: "#9a6bea", right: "#7448c4" });
  cubes.push({ i: 3, j: 2, k: 3, top: "#8fb0ff", left: "#4767d6", right: "#3550ad" });
  return cubes.sort((a, b) => a.k - b.k || a.i + a.j - (b.i + b.j));
}
const ISLAND = buildIsland();

export function Island() {
  const pts = (a: [number, number][]) => a.map((p) => p.join(",")).join(" ");
  return (
    <svg viewBox="-170 -60 340 300" className="island" aria-hidden>
      <defs>
        <radialGradient id="island-glow" cx="50%" cy="50%" r="50%">
          <stop offset="0" stopColor="#6C8CFF" stopOpacity=".35" />
          <stop offset="1" stopColor="#6C8CFF" stopOpacity="0" />
        </radialGradient>
      </defs>
      <ellipse cx="0" cy="230" rx="110" ry="14" fill="url(#island-glow)" className="island-shadow" />
      <g className="island-body">
        {ISLAND.map((c, n) => {
          const x = (c.i - c.j) * W;
          const y = (c.i + c.j) * H - c.k * Z + 40;
          return (
            <g key={n}>
              <polygon points={pts([[x - W, y + H], [x, y + 2 * H], [x, y + 2 * H + Z], [x - W, y + H + Z]])} fill={c.left} />
              <polygon points={pts([[x + W, y + H], [x, y + 2 * H], [x, y + 2 * H + Z], [x + W, y + H + Z]])} fill={c.right} />
              <polygon points={pts([[x, y], [x + W, y + H], [x, y + 2 * H], [x - W, y + H]])} fill={c.top} />
            </g>
          );
        })}
      </g>
      <g className="island-bits" fill="#8fb0ff">
        <rect x="-135" y="40" width="9" height="9" rx="2" opacity=".7" />
        <rect x="120" y="90" width="12" height="12" rx="2" fill="#c9a7ff" opacity=".7" />
        <rect x="100" y="10" width="7" height="7" rx="2" fill="#5be6b0" opacity=".8" />
      </g>
    </svg>
  );
}
