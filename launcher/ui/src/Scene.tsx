import { useEffect, useRef } from "react";
import * as THREE from "three";

export interface Palette {
  top: string;
  dirt: string;
  stone: string;
  leaf: string;
  accent: string;
}

export const PALETTES: Record<string, Palette> = {
  vanilla: { top: "#6fd05c", dirt: "#8a5a3a", stone: "#9299aa", leaf: "#3f9d3c", accent: "#5be6b0" },
  fabric: { top: "#5fd9c0", dirt: "#7d6a5a", stone: "#8b98b3", leaf: "#2fa3b0", accent: "#6C8CFF" },
  quilt: { top: "#b884ff", dirt: "#6d4f7d", stone: "#8a84b4", leaf: "#8e52dc", accent: "#e070c8" },
  forge: { top: "#d99a55", dirt: "#6e4630", stone: "#8c8c9c", leaf: "#c25b2c", accent: "#ff8a3d" },
  neoforge: { top: "#f2b552", dirt: "#7a5230", stone: "#958c84", leaf: "#d4822c", accent: "#ffc857" },
};

function rng(seed: number) {
  return () => {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// 16x16 pixel-art texture painter, redrawn when the palette changes.
function paint(canvas: HTMLCanvasElement, base: string, opts: { lip?: string; speckle?: number; seed: number }) {
  const g = canvas.getContext("2d")!;
  const r = rng(opts.seed);
  const c = new THREE.Color(base);
  for (let y = 0; y < 16; y++)
    for (let x = 0; x < 16; x++) {
      const v = 0.82 + r() * 0.3 + (r() < (opts.speckle ?? 0.08) ? -0.22 : 0);
      g.fillStyle = `rgb(${Math.min(255, c.r * 255 * v) | 0},${Math.min(255, c.g * 255 * v) | 0},${Math.min(255, c.b * 255 * v) | 0})`;
      g.fillRect(x, y, 1, 1);
    }
  if (opts.lip) {
    const l = new THREE.Color(opts.lip);
    for (let x = 0; x < 16; x++) {
      const h = 3 + ((r() * 3) | 0);
      for (let y = 0; y < h; y++) {
        const v = 0.85 + r() * 0.3;
        g.fillStyle = `rgb(${Math.min(255, l.r * 255 * v) | 0},${Math.min(255, l.g * 255 * v) | 0},${Math.min(255, l.b * 255 * v) | 0})`;
        g.fillRect(x, y, 1, 1);
      }
    }
  }
}

function tex(canvas: HTMLCanvasElement) {
  const t = new THREE.CanvasTexture(canvas);
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestFilter;
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

const mkCanvas = () => Object.assign(document.createElement("canvas"), { width: 16, height: 16 });

export type Intensity = "idle" | "preparing" | "running";

export function Scene({ loader, intensity }: { loader: string; intensity: Intensity }) {
  const host = useRef<HTMLDivElement>(null);
  const api = useRef<{ setPalette: (p: Palette) => void; setIntensity: (i: Intensity) => void } | null>(null);

  useEffect(() => {
    const el = host.current!;
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "high-performance" });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.15;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFShadowMap;
    el.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 100);
    camera.position.set(25, 17, 27);
    const look = new THREE.Vector3(0, -2.2, 0);

    // ---- lights
    scene.add(new THREE.HemisphereLight(0xb9c8ff, 0x4a3a8a, 1.9));
    const sun = new THREE.DirectionalLight(0xffe6c4, 3.4);
    sun.position.set(7, 12, 5);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    Object.assign(sun.shadow.camera, { left: -9, right: 9, top: 9, bottom: -9, near: 1, far: 40 });
    sun.shadow.bias = -0.0006;
    scene.add(sun);
    const rim = new THREE.PointLight(0x8a6cff, 90, 30);
    rim.position.set(-9, 3, -8);
    scene.add(rim);

    // ---- materials (pixel textures)
    const cTop = mkCanvas(), cSide = mkCanvas(), cDirt = mkCanvas(), cStone = mkCanvas();
    const tTop = tex(cTop), tSide = tex(cSide), tDirt = tex(cDirt), tStone = tex(cStone);
    const mat = (map: THREE.Texture) => new THREE.MeshStandardMaterial({ map, roughness: 0.95, metalness: 0 });
    const mTop = mat(tTop), mSide = mat(tSide), mDirt = mat(tDirt), mStone = mat(tStone);
    const mLeaf = new THREE.MeshStandardMaterial({ roughness: 1, flatShading: true });
    const mTrunk = new THREE.MeshStandardMaterial({ color: 0x6b4a32, roughness: 1 });
    const accentMat = new THREE.MeshStandardMaterial({ roughness: 0.2, metalness: 0.1, transparent: true, opacity: 0.92, emissiveIntensity: 1.4 });

    // ---- terrain
    const R = rng(7);
    const N = 13, C = 6;
    const noise = (x: number, z: number) => Math.sin(x * 0.9 + 1.3) * Math.cos(z * 0.8 + 0.4) * 0.5 + Math.sin((x + z) * 0.45) * 0.5;
    type Col = { x: number; z: number; top: number; depth: number };
    const cols: Col[] = [];
    for (let x = 0; x < N; x++)
      for (let z = 0; z < N; z++) {
        const d = Math.hypot(x - C, z - C) / C;
        const edge = d + noise(x * 1.7, z * 1.3) * 0.13;
        if (edge > 0.98) continue;
        cols.push({ x: x - C, z: z - C, top: Math.round(noise(x, z) * 0.9 + (d < 0.25 ? 0.8 : 0)), depth: Math.max(1, Math.round((1 - edge) * 9 + R() * 1.4)) });
      }
    const grass: THREE.Vector3[] = [], dirt: THREE.Vector3[] = [], stone: THREE.Vector3[] = [];
    for (const c of cols) {
      grass.push(new THREE.Vector3(c.x, c.top, c.z));
      for (let k = 1; k <= c.depth; k++) (k <= 2 ? dirt : stone).push(new THREE.Vector3(c.x, c.top - k, c.z));
    }
    const box = new THREE.BoxGeometry(1, 1, 1);
    const island = new THREE.Group();
    scene.add(island);
    const inst = (pos: THREE.Vector3[], material: THREE.Material | THREE.Material[], seed: number) => {
      const m = new THREE.InstancedMesh(box, material, pos.length);
      const r = rng(seed), o = new THREE.Object3D(), col = new THREE.Color();
      pos.forEach((p, i) => {
        o.position.copy(p);
        o.updateMatrix();
        m.setMatrixAt(i, o.matrix);
        const v = 0.86 + r() * 0.22;
        m.setColorAt(i, col.setRGB(v, v, v));
      });
      m.castShadow = m.receiveShadow = true;
      island.add(m);
      return m;
    };
    inst(grass, [mSide, mSide, mTop, mDirt, mSide, mSide], 1);
    inst(dirt, mDirt, 2);
    inst(stone, mStone, 3);

    // ---- trees
    const trunks: THREE.Vector3[] = [], leaves: THREE.Vector3[] = [];
    const spots = cols.filter((c) => Math.hypot(c.x, c.z) > 2.2 && Math.hypot(c.x, c.z) < 4.6 && R() < 0.16).slice(0, 4);
    for (const s of spots) {
      const h = 1 + ((R() * 2) | 0);
      for (let y = 1; y <= h; y++) trunks.push(new THREE.Vector3(s.x, s.top + y, s.z));
      for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) for (let dy = 0; dy < 2; dy++)
        if (dy === 0 || Math.abs(dx) + Math.abs(dz) <= 1) if (!(dx === 0 && dz === 0 && dy === 0)) leaves.push(new THREE.Vector3(s.x + dx, s.top + h + dy, s.z + dz));
    }
    inst(trunks, mTrunk, 4);
    inst(leaves, mLeaf, 5);

    // ---- crystals (accent)
    const crystals: { m: THREE.Mesh; base: number; ph: number }[] = [];
    const crystalLight = new THREE.PointLight(0xffffff, 14, 9);
    crystalLight.position.set(0, 3.2, 0);
    island.add(crystalLight);
    [[0.2, 2.6, 0.2, 0.62], [-2.4, 2.0, 1.6, 0.36], [2.5, 1.8, -1.9, 0.42]].forEach(([x, y, z, s], i) => {
      const m = new THREE.Mesh(new THREE.OctahedronGeometry(1, 0), accentMat);
      m.scale.set(s * 0.7, s * 1.3, s * 0.7);
      m.position.set(x, y, z);
      m.castShadow = true;
      island.add(m);
      crystals.push({ m, base: y, ph: i * 2.1 });
    });

    // ---- clouds
    const clouds: THREE.Group[] = [];
    const mCloud = new THREE.MeshStandardMaterial({ color: 0xdfe6ff, roughness: 1, transparent: true, opacity: 0.13, emissive: 0x8a9cff, emissiveIntensity: 0.6, depthWrite: false });
    for (let i = 0; i < 4; i++) {
      const g = new THREE.Group();
      const n = 3 + ((R() * 3) | 0);
      for (let k = 0; k < n; k++) {
        const b = new THREE.Mesh(box, mCloud);
        b.scale.set(1.6 + R() * 1.6, 0.6 + R() * 0.3, 1.2 + R() * 1);
        b.position.set(k * 1.5 - n * 0.7, R() * 0.3, R() * 0.8);
        g.add(b);
      }
      g.position.set(-22 + R() * 44, -11 + R() * 3, -6 + R() * 10 - (i % 2) * 8);
      g.userData.speed = 0.15 + R() * 0.25;
      clouds.push(g);
      scene.add(g);
    }

    // ---- fireflies / dust
    const P = 90;
    const pPos = new Float32Array(P * 3), pSeed = new Float32Array(P);
    for (let i = 0; i < P; i++) {
      const a = R() * Math.PI * 2, rr = 2 + R() * 7;
      pPos.set([Math.cos(a) * rr, -2 + R() * 8, Math.sin(a) * rr], i * 3);
      pSeed[i] = R() * 100;
    }
    const pGeo = new THREE.BufferGeometry();
    pGeo.setAttribute("position", new THREE.BufferAttribute(pPos.slice(), 3));
    const sprite = document.createElement("canvas");
    sprite.width = sprite.height = 64;
    const sg = sprite.getContext("2d")!;
    const grd = sg.createRadialGradient(32, 32, 0, 32, 32, 32);
    grd.addColorStop(0, "rgba(255,255,255,1)");
    grd.addColorStop(0.3, "rgba(255,255,255,.35)");
    grd.addColorStop(1, "rgba(255,255,255,0)");
    sg.fillStyle = grd;
    sg.fillRect(0, 0, 64, 64);
    const pMat = new THREE.PointsMaterial({ size: 0.34, map: new THREE.CanvasTexture(sprite), transparent: true, depthWrite: false, blending: THREE.AdditiveBlending });
    const points = new THREE.Points(pGeo, pMat);
    scene.add(points);

    // ---- palette / intensity control
    let boost = 0, boostTarget = 0;
    const setPalette = (p: Palette) => {
      paint(cTop, p.top, { seed: 11 });
      paint(cSide, p.dirt, { seed: 12, lip: p.top });
      paint(cDirt, p.dirt, { seed: 13, speckle: 0.14 });
      paint(cStone, p.stone, { seed: 14, speckle: 0.2 });
      [tTop, tSide, tDirt, tStone].forEach((t) => (t.needsUpdate = true));
      mLeaf.color.set(p.leaf);
      accentMat.color.set(p.accent);
      accentMat.emissive.set(p.accent);
      crystalLight.color.set(p.accent);
      pMat.color.set(p.accent);
    };
    api.current = { setPalette, setIntensity: (i) => (boostTarget = i === "idle" ? 0 : i === "preparing" ? 1 : 0.5) };
    setPalette(PALETTES[loader] ?? PALETTES.fabric);
    api.current.setIntensity(intensity);

    // ---- sizing, parallax, loop
    let w = 1, h = 1;
    const resize = () => {
      w = el.clientWidth || 1;
      h = el.clientHeight || 1;
      renderer.setSize(w, h);
      camera.aspect = w / h;
      // push the island to the right so the copy on the left has room
      const shift = w > 900 ? 0.2 : 0;
      camera.setViewOffset(w, h, -w * shift, h * 0.02, w, h);
      camera.updateProjectionMatrix();
    };
    const ro = new ResizeObserver(resize);
    ro.observe(el);
    resize();

    const mouse = { x: 0, y: 0, sx: 0, sy: 0 };
    const onMove = (e: MouseEvent) => {
      mouse.x = (e.clientX / window.innerWidth) * 2 - 1;
      mouse.y = (e.clientY / window.innerHeight) * 2 - 1;
    };
    window.addEventListener("mousemove", onMove);

    const t0 = performance.now();
    let last = t0;
    let raf = 0;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const frame = () => {
      raf = requestAnimationFrame(frame);
      if (document.hidden) return;
      const now = performance.now();
      const dt = Math.min((now - last) / 1000, 0.05), t = (now - t0) / 1000;
      last = now;
      boost += (boostTarget - boost) * Math.min(1, dt * 3);
      mouse.sx += (mouse.x - mouse.sx) * Math.min(1, dt * 3);
      mouse.sy += (mouse.y - mouse.sy) * Math.min(1, dt * 3);

      island.rotation.y = -0.5 + t * (reduce ? 0 : 0.08 + boost * 0.5) + mouse.sx * 0.35;
      island.position.y = reduce ? 0 : Math.sin(t * 0.9) * 0.28;
      camera.position.y = 17 - mouse.sy * 2;
      camera.lookAt(look);

      crystals.forEach((c, i) => {
        c.m.rotation.y = t * (0.7 + boost * 2.5) + c.ph;
        c.m.position.y = c.base + (reduce ? 0 : Math.sin(t * 1.4 + c.ph) * 0.18);
        void i;
      });
      accentMat.emissiveIntensity = 1.2 + boost * 1.8 + Math.sin(t * 2) * 0.15;
      crystalLight.intensity = 14 + boost * 40;

      clouds.forEach((c) => {
        c.position.x += dt * c.userData.speed * (reduce ? 0 : 1);
        if (c.position.x > 24) c.position.x = -24;
      });

      const arr = pGeo.attributes.position.array as Float32Array;
      for (let i = 0; i < P; i++) {
        const s = pSeed[i];
        arr[i * 3] = pPos[i * 3] + Math.sin(t * 0.4 + s) * 0.6;
        arr[i * 3 + 1] = pPos[i * 3 + 1] + Math.sin(t * 0.6 + s * 2) * 0.5 + (reduce ? 0 : ((t * (0.15 + boost * 0.8) + s) % 4) - 2) * 0.4;
        arr[i * 3 + 2] = pPos[i * 3 + 2] + Math.cos(t * 0.35 + s) * 0.6;
      }
      pGeo.attributes.position.needsUpdate = true;
      renderer.render(scene, camera);
    };
    frame();

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
      window.removeEventListener("mousemove", onMove);
      scene.traverse((o) => {
        const m = o as THREE.Mesh;
        m.geometry?.dispose?.();
      });
      [mTop, mSide, mDirt, mStone, mLeaf, mTrunk, accentMat, mCloud, pMat].forEach((m) => m.dispose());
      renderer.dispose();
      renderer.domElement.remove();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    api.current?.setPalette(PALETTES[loader] ?? PALETTES.fabric);
  }, [loader]);
  useEffect(() => {
    api.current?.setIntensity(intensity);
  }, [intensity]);

  return <div className="scene" ref={host} />;
}
