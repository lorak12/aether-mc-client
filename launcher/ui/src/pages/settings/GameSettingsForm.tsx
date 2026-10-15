import { ReactNode, useEffect, useState } from "react";
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import { api } from "../../api";
import type { GameSettings, GC, JavaInstall, Overrides, SettingKey } from "../../api/types";
import { errText } from "../../api";
import { ICheck, IFolder, IPlus, IRefresh, ITrash, IWarning } from "../../components/Icons";
import { Checkbox, Segmented, Slider, Spinner, Toggle } from "../../components/ui";
import { megabytes } from "../../lib/format";
import { isOverridden, setOverride, setOverrideValue } from "../../lib/overrides";

let memCache: number | null = null;
export function useSystemMemory() {
  const [mb, setMb] = useState(memCache ?? 16384);
  useEffect(() => {
    if (memCache) return;
    api
      .systemMemoryMb()
      .then((m) => {
        memCache = typeof m === "number" && m > 0 ? m : 16384;
        setMb(memCache);
      })
      .catch(() => {});
  }, []);
  return mb;
}

const GCS: { id: GC; label: string }[] = [
  { id: "G1GC", label: "G1 (default)" },
  { id: "ZGC", label: "ZGC" },
  { id: "Shenandoah", label: "Shenandoah" },
  { id: "Parallel", label: "Parallel" },
];

/**
 * Java and game settings. Without `overrides` it edits the global defaults; with them, every row gets an
 * "Override global setting" checkbox and shows the global value greyed out until it's ticked.
 */
export function GameSettingsForm({
  global,
  onGlobal,
  overrides,
  onOverrides,
}: {
  global: GameSettings;
  onGlobal?: (s: GameSettings) => void;
  overrides?: Overrides;
  onOverrides?: (o: Overrides) => void;
}) {
  const sysMem = useSystemMemory();
  const profile = !!overrides;
  const val = <K extends SettingKey>(k: K): GameSettings[K] => (overrides && isOverridden(overrides, k) ? (overrides[k]!.value as GameSettings[K]) : global[k]);
  const set = <K extends SettingKey>(k: K, v: GameSettings[K]) => (overrides ? onOverrides!(setOverrideValue(overrides, k, v)) : onGlobal!({ ...global, [k]: v }));
  const off = (k: SettingKey) => profile && !isOverridden(overrides!, k);

  const rowProps = (k: SettingKey, also: SettingKey[] = []) => {
    const keys = [k, ...also];
    return {
      inherited: off(k),
      override: profile
        ? { on: keys.every((x) => isOverridden(overrides!, x)), toggle: (v: boolean) => onOverrides!(keys.reduce((o, x) => setOverride(o, x, v, global), overrides!)) }
        : undefined,
    };
  };

  const env = val("env");
  const recommended = Math.min(sysMem / 2, sysMem >= 16384 ? 6144 : 4096);

  return (
    <div className="gs-form">
      <h4 className="gs-section">Java</h4>
      <Row {...rowProps("javaPath")} label="Java" hint="Automatic downloads the Java version each Minecraft version needs. Pick your own only if you know you need it.">
        <JavaPicker value={val("javaPath")} disabled={off("javaPath")} onChange={(v) => set("javaPath", v)} />
      </Row>
      <Row {...rowProps("memoryMb")} label="Memory" hint={`${megabytes(recommended)} suits most modded profiles.`}>
        <Slider
          value={val("memoryMb")}
          min={512}
          max={Math.max(1024, Math.floor(sysMem / 256) * 256)}
          step={256}
          unit="MB"
          disabled={off("memoryMb")}
          format={megabytes}
          onChange={(v) => set("memoryMb", v)}
          marks={[
            { at: recommended, label: "Recommended" },
            { at: Math.floor(sysMem / 256) * 256, label: megabytes(sysMem) },
          ]}
        />
      </Row>
      <Row {...rowProps("gc")} label="Garbage collector" hint="G1 works best for most players; ZGC can reduce stutter with 8 GB or more.">
        <Segmented value={val("gc")} options={GCS} onChange={(v) => !off("gc") && set("gc", v)} size="sm" />
      </Row>
      <Row {...rowProps("jvmArgs")} label="JVM arguments" hint="Separated by spaces. Use quotes for values with spaces.">
        <input value={val("jvmArgs")} disabled={off("jvmArgs")} onChange={(e) => set("jvmArgs", e.target.value)} placeholder="-XX:+UnlockExperimentalVMOptions" spellCheck={false} className="mono" />
      </Row>

      <h4 className="gs-section">Window</h4>
      <Row {...rowProps("width", ["height", "fullscreen"])} label="Game resolution">
        <div className="row">
          <input type="number" className="num" value={val("width")} disabled={off("width")} min={640} onChange={(e) => set("width", Number(e.target.value) || 0)} aria-label="Width" />
          <span className="muted">×</span>
          <input type="number" className="num" value={val("height")} disabled={off("height")} min={480} onChange={(e) => set("height", Number(e.target.value) || 0)} aria-label="Height" />
          <label className="row small" style={{ marginLeft: 12 }}>
            <Toggle on={val("fullscreen")} disabled={off("fullscreen")} onChange={(v) => set("fullscreen", v)} label="Fullscreen" /> Fullscreen
          </label>
        </div>
      </Row>

      <h4 className="gs-section">Minecraft settings</h4>
      <Row
        {...rowProps("shareOptions")}
        label="Same settings in every profile"
        hint="Video settings, keybinds, sound and controls carry over to every profile, modpacks included. Resource packs stay per profile."
      >
        <Toggle on={val("shareOptions")} disabled={off("shareOptions")} onChange={(v) => set("shareOptions", v)} label="Same settings in every profile" />
      </Row>

      <h4 className="gs-section">Launch hooks</h4>
      <p className="muted small">Commands run in the game folder with INST_NAME, INST_DIR, INST_MC_DIR and INST_JAVA set.</p>
      <Row {...rowProps("preLaunch")} label="Pre-launch command" hint="Runs before the game starts. If it fails, the game doesn't start.">
        <input value={val("preLaunch")} disabled={off("preLaunch")} onChange={(e) => set("preLaunch", e.target.value)} className="mono" spellCheck={false} />
      </Row>
      <Row {...rowProps("wrapper")} label="Wrapper command" hint="Starts Java through this program, e.g. prime-run or a profiler.">
        <input value={val("wrapper")} disabled={off("wrapper")} onChange={(e) => set("wrapper", e.target.value)} className="mono" spellCheck={false} />
      </Row>
      <Row {...rowProps("postExit")} label="Post-exit command" hint="Runs after the game closes.">
        <input value={val("postExit")} disabled={off("postExit")} onChange={(e) => set("postExit", e.target.value)} className="mono" spellCheck={false} />
      </Row>
      <Row {...rowProps("env")} label="Environment variables" hint="Set for the game and the hooks.">
        <div className="env-table">
          {env.map(([k, v], i) => (
            <div key={i} className="env-row">
              <input value={k} disabled={off("env")} placeholder="NAME" className="mono" onChange={(e) => set("env", env.map((r, j) => (j === i ? [e.target.value, r[1]] : r)) as [string, string][])} />
              <input value={v} disabled={off("env")} placeholder="value" className="mono" onChange={(e) => set("env", env.map((r, j) => (j === i ? [r[0], e.target.value] : r)) as [string, string][])} />
              <button className="icon-btn sm" disabled={off("env")} aria-label="Remove variable" onClick={() => set("env", env.filter((_, j) => j !== i))}>
                <ITrash size={15} />
              </button>
            </div>
          ))}
          <button className="btn sm" disabled={off("env")} onClick={() => set("env", [...env, ["", ""]])}>
            <IPlus size={15} /> Add variable
          </button>
        </div>
      </Row>
    </div>
  );
}

function Row({
  inherited,
  override,
  label,
  hint,
  children,
}: {
  inherited: boolean;
  override?: { on: boolean; toggle: (v: boolean) => void };
  label: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className={inherited ? "gs-row inherited" : "gs-row"}>
      <div className="gs-label">
        <span>{label}</span>
        {hint && <span className="hint">{hint}</span>}
      </div>
      <div className="gs-control">{children}</div>
      {override && (
        <div className="gs-override">
          <Checkbox checked={override.on} onChange={override.toggle}>
            Override global
          </Checkbox>
        </div>
      )}
    </div>
  );
}

let javaCache: JavaInstall[] | null = null;

/** Automatic (Mojang's runtime) or a Java found on this computer, or any java executable picked by hand. */
function JavaPicker({ value, disabled, onChange }: { value: string; disabled: boolean; onChange: (v: string) => void }) {
  const [list, setList] = useState<JavaInstall[] | null>(javaCache);
  const [scanning, setScanning] = useState(false);
  const [check, setCheck] = useState<{ ok: true; j: JavaInstall } | { ok: false; err: string } | null>(null);
  const scan = () => {
    setScanning(true);
    api
      .listJavas()
      .then((l) => {
        javaCache = l;
        setList(l);
      })
      .catch(() => setList([]))
      .finally(() => setScanning(false));
  };
  useEffect(() => {
    if (!javaCache) scan();
  }, []);
  useEffect(() => {
    setCheck(null);
    if (!value) return;
    let alive = true;
    api
      .probeJava(value)
      .then((j) => alive && setCheck({ ok: true, j }))
      .catch((e) => alive && setCheck({ ok: false, err: errText(e) }));
    return () => {
      alive = false;
    };
  }, [value]);
  const known = list?.some((j) => j.path === value);
  return (
    <div className="stack tight">
      <div className="input-with-btn">
        <select value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)} aria-label="Java">
          <option value="">Automatic (recommended)</option>
          {(list ?? []).map((j) => (
            <option key={j.path} value={j.path}>
              Java {j.major} ({j.version}, {j.vendor || "unknown vendor"}) {j.path}
            </option>
          ))}
          {value && !known && <option value={value}>{value}</option>}
        </select>
        <button className="btn" disabled={disabled || scanning} onClick={scan} title="Look for Java again" aria-label="Look for Java again">
          {scanning ? <Spinner size={14} /> : <IRefresh size={15} />}
        </button>
        <button
          className="btn"
          disabled={disabled}
          onClick={async () => {
            try {
              const f = await openDialog({ title: "Choose a java executable", filters: [{ name: "Java", extensions: navigator.userAgent.includes("Windows") ? ["exe"] : ["*"] }] });
              if (typeof f === "string") onChange(f);
            } catch {
              /* no dialog in a browser */
            }
          }}
        >
          <IFolder size={16} /> Browse
        </button>
      </div>
      {value && check && (
        <span className={check.ok ? "hint ok-text" : "form-error"}>
          {check.ok ? (
            <>
              <ICheck size={13} /> Java {check.j.major} ({check.j.version}). Minecraft 1.20.5+ needs 21, 1.18–1.20.4 needs 17, older versions need 8.
            </>
          ) : (
            <>
              <IWarning size={13} /> {check.err}
            </>
          )}
        </span>
      )}
    </div>
  );
}
