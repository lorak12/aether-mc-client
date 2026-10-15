import { useCallback, useState } from "react";
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import { api, errText } from "../../api";
import type { Loader, ProfileKind } from "../../api/types";
import { useApp } from "../../app/store";
import { Modal } from "../../components/overlays";
import { Field, Segmented, Spinner } from "../../components/ui";
import { clientSupported, listText } from "../../lib/versions";
import { LoaderChoice, LoaderPicker, VersionSelect } from "./pickers";

export function NewProfileModal() {
  const app = useApp();
  const open = app.newProfileOpen;
  const [kind, setKind] = useState<ProfileKind | "import">("client");
  const [name, setName] = useState("");
  const [version, setVersion] = useState("");
  const [loader, setLoader] = useState<LoaderChoice>("fabric");
  const [busy, setBusy] = useState(false);
  const onVersion = useCallback((v: string) => setVersion(v), []);

  const close = () => {
    app.setNewProfileOpen(false);
    setBusy(false);
  };

  const realLoader: Loader = loader === "builtin" ? "fabric" : loader;
  const defaultName = kind === "vanilla" ? `Minecraft ${version}` : `Kite ${version}`;

  const create = async () => {
    setBusy(true);
    try {
      const created = await api.createProfile(name.trim() || defaultName, version, realLoader);
      const p = await api.updateProfile({ ...created, meta: { ...created.meta, kind: kind as ProfileKind, builtinOnly: loader === "builtin" } });
      await app.refreshProfiles();
      app.setActive(p.id);
      app.toast(`Created ${p.name}`, { tone: "success" });
      close();
      setName("");
      app.navigate({ page: "profiles", open: p.id });
    } catch (e) {
      app.error(e);
      setBusy(false);
    }
  };

  const importPack = async () => {
    let path: string | null = null;
    try {
      const r = await openDialog({ filters: [{ name: "Modpack (Modrinth .mrpack or CurseForge .zip)", extensions: ["mrpack", "zip"] }], title: "Import a modpack" });
      path = typeof r === "string" ? r : null;
    } catch {
      app.toast("Importing needs the desktop app");
    }
    if (!path) return;
    setBusy(true);
    const note = app.pushNotification({ kind: "download", title: "Importing modpack", detail: path.split(/[\\/]/).pop() ?? path, progress: 0.1 });
    try {
      const p = await api.importPack(path);
      await app.refreshProfiles();
      app.setActive(p.id);
      app.updateNotification(note, { title: `Imported ${p.name}`, detail: `${p.mc_version} ${p.loader}`, progress: undefined, action: { label: "Open", route: { page: "profiles", open: p.id } } });
      close();
    } catch (e) {
      app.updateNotification(note, { kind: "crash", title: "Couldn't import the modpack", detail: errText(e), progress: undefined });
      app.error(e);
      setBusy(false);
    }
  };

  const unsupported = kind === "client" && version && app.aetherVersions.length > 0 && !clientSupported(version, realLoader, app.aetherVersions);

  return (
    <Modal
      open={open}
      onClose={close}
      title="New profile"
      width={520}
      footer={
        kind === "import" ? (
          <>
            <span className="grow" />
            <button className="btn ghost" onClick={close}>
              Cancel
            </button>
            <button className="btn primary" disabled={busy} onClick={importPack}>
              {busy && <Spinner />} Choose a modpack file
            </button>
          </>
        ) : (
          <>
            <span className="grow" />
            <button className="btn ghost" onClick={close}>
              Cancel
            </button>
            <button className="btn primary" disabled={busy || !version} onClick={create}>
              {busy && <Spinner />} Create profile
            </button>
          </>
        )
      }
    >
      <div className="stack">
        <Segmented
          value={kind}
          onChange={(k) => {
            setKind(k);
            setLoader(k === "vanilla" ? "vanilla" : "fabric");
          }}
          options={[
            { id: "client", label: "Kite client" },
            { id: "vanilla", label: "Vanilla Minecraft" },
            { id: "import", label: "Import modpack" },
          ]}
        />
        {kind === "import" ? (
          <p className="muted">Pick a Modrinth .mrpack or a CurseForge modpack .zip. Its mods, configs and loader are set up in a new profile. To browse modpacks instead, open Content and choose Modpacks.</p>
        ) : (
          <>
            <Field label="Name">
              <input value={name} onChange={(e) => setName(e.target.value)} placeholder={defaultName} autoFocus maxLength={48} />
            </Field>
            <Field label="Game version">
              <VersionSelect value={version} onChange={onVersion} />
            </Field>
            <Field
              label="Loader"
              hint={
                kind === "client"
                  ? loader === "builtin"
                    ? "Vanilla loads only Kite's built-in mods, nothing from other mod loaders."
                    : "Kite's built-in mods plus any Fabric or Quilt mods you add."
                  : undefined
              }
            >
              <LoaderPicker kind={kind} value={loader} onChange={setLoader} />
            </Field>
            {unsupported && (
              <p className="note warning">
                {clientSupported(version, "fabric", app.aetherVersions)
                  ? `Quilt doesn't exist for ${version}. Pick Fabric (the launcher installs Legacy Fabric) to play with Kite's built-in mods.`
                  : `The Kite client runs on one release of each version: ${listText(app.aetherVersions)}. On ${version} the game starts without Kite's built-in mods.`}
              </p>
            )}
          </>
        )}
      </div>
    </Modal>
  );
}
