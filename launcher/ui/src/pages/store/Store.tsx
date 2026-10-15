import { CSSProperties, useState, useEffect } from "react";
import { api, balanceOf } from "../../api";
import type { Rarity, Slot, StoreData, StoreItem } from "../../api/types";
import { useApp } from "../../app/store";
import { useAsync } from "../../components/hooks";
import { ICheck, ICoin, IShirt, IStore } from "../../components/Icons";
import { Modal } from "../../components/overlays";
import { EmptyState, ErrorCard, Skeleton, Spinner, Tabs } from "../../components/ui";
import { cap } from "../../lib/format";
import { SocialUnavailable } from "../../shell/RightSidebar";

type Tab = "featured" | "cape" | "wings" | "hat" | "other" | "owned";
const TABS: { id: Tab; label: string }[] = [
  { id: "featured", label: "Featured" },
  { id: "cape", label: "Capes" },
  { id: "wings", label: "Wings" },
  { id: "hat", label: "Hats" },
  { id: "other", label: "Other" },
  { id: "owned", label: "Your wardrobe" },
];
const MAIN: Slot[] = ["cape", "wings", "hat"];
const SLOT_NAME: Record<Slot, string> = { cape: "cape", wings: "wings", hat: "hat", ears: "ears", face: "face item", boots: "boots", back: "back item" };
const RARITY_ART: Record<Rarity, string> = {
  common: "radial-gradient(90% 80% at 50% 0%, #8fb0ff33, transparent 70%), linear-gradient(160deg, #1a2030, #0d1018)",
  rare: "radial-gradient(90% 80% at 50% 0%, #6c8cff66, transparent 70%), linear-gradient(160deg, #18213d, #0b0f1c)",
  epic: "radial-gradient(90% 80% at 50% 0%, #b36cff77, transparent 70%), linear-gradient(160deg, #24173d, #0f0b1c)",
  legendary: "radial-gradient(90% 80% at 50% 0%, #ffc85788, transparent 70%), linear-gradient(160deg, #3a2a12, #140f08)",
};

export function StorePage({ tab: initial }: { tab?: string }) {
  const app = useApp();
  const tab = (TABS.some((t) => t.id === initial) ? initial : "featured") as Tab;
  const enabled = !!app.online?.signed_in;
  const data = useAsync<StoreData | null>(() => (enabled ? api.store() : Promise.resolve(null)), [enabled]);
  const [open, setOpen] = useState<StoreItem | null>(null);
  // The game changes the loadout too (Right Shift > Cosmetics): refresh quietly while the store is open.
  const setStore = data.setData;
  useEffect(() => {
    if (!enabled) return;
    const refresh = () => {
      if (document.visibilityState === "visible") api.store().then((fresh) => setStore(() => fresh), () => undefined);
    };
    const timer = window.setInterval(refresh, 20_000);
    window.addEventListener("focus", refresh);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("focus", refresh);
    };
  }, [enabled, setStore]);

  if (!enabled)
    return (
      <div className="page store-page">
        <header className="page-head">
          <h1>Store</h1>
        </header>
        <SocialUnavailable />
      </div>
    );

  const d = data.data;
  const owned = new Set(d?.owned ?? []);
  const equipped = new Set(Object.values(d?.loadout ?? {}));
  const list = (d?.items ?? []).filter((i) =>
    tab === "featured" ? i.price !== null && (i.rarity === "legendary" || i.rarity === "epic") : tab === "owned" ? owned.has(i.id) : tab === "other" ? !MAIN.includes(i.slot) : i.slot === tab,
  );
  const sorted = [...list].sort((a, b) => Number(owned.has(a.id)) - Number(owned.has(b.id)) || (b.price ?? 0) - (a.price ?? 0) || a.name.localeCompare(b.name));
  const update = (patch: Partial<StoreData>) => data.setData((x) => (x ? { ...x, ...patch } : x));

  return (
    <div className="page store-page">
      <header className="page-head">
        <h1>Store</h1>
        <button className="coins-chip lg" onClick={() => app.navigate({ page: "coins" })}>
          <ICoin size={18} />
          <span>{(app.coins ?? 0).toLocaleString("en-US")}</span>
          <span className="muted">Get coins</span>
        </button>
      </header>
      <Tabs<Tab> value={tab} onChange={(t) => app.replace({ page: "store", tab: t })} tabs={TABS.map((t) => (t.id === "owned" ? { ...t, count: owned.size } : t))} />

      {data.error ? (
        <ErrorCard message={data.error} retry={data.reload} />
      ) : !d ? (
        <div className="item-grid">
          {Array.from({ length: 8 }, (_, i) => (
            <Skeleton key={i} h={240} r={16} />
          ))}
        </div>
      ) : sorted.length === 0 ? (
        <EmptyState icon={<IStore size={22} />} title={tab === "owned" ? "Nothing here yet" : "No items in this category"} text={tab === "owned" ? "Free starter items appear here once the store loads." : "New items arrive with client updates."} />
      ) : (
        <>
          <div className={tab === "featured" ? "item-grid featured" : "item-grid"}>
            {sorted.map((it) => (
              <ItemCard key={it.id} item={it} owned={owned.has(it.id)} equipped={equipped.has(it.id)} onOpen={() => setOpen(it)} />
            ))}
          </div>
          <p className="hint">Equipped items show on you in game for everyone running Kite Client.</p>
        </>
      )}

      <ItemModal
        item={open}
        owned={!!open && owned.has(open.id)}
        equipped={!!open && equipped.has(open.id)}
        onClose={() => setOpen(null)}
        onBought={(o) => update({ owned: o })}
        onLoadout={(l) => update({ loadout: l })}
      />
    </div>
  );
}

/** Store art: the model's icon, or the front of a cape texture (any resolution, animated strips show frame one). */
export function ItemArt({ item, size = "md" }: { item: StoreItem; size?: "md" | "lg" }) {
  const app = useApp();
  const [failed, setFailed] = useState(false);
  const [frames, setFrames] = useState(1);
  const base = app.cfg?.backend_url ?? "";
  const src = item.icon.startsWith("http") ? item.icon : base + item.icon;
  if (failed || !base)
    return (
      <span className={`item-art ${size}`} style={{ background: RARITY_ART[item.rarity] }}>
        <IShirt size={size === "lg" ? 64 : 44} className="glyph" />
      </span>
    );
  if (item.slot === "cape") {
    // Vanilla cape layout: the outside is at (1,1)-(11,17) of a 64x32 sheet.
    const style = {
      backgroundImage: `url("${src}")`,
      backgroundSize: `640% ${200 * frames}%`,
      backgroundPosition: `${(1 / 54) * 100}% ${(1 / (32 * frames - 16)) * 100}%`,
    } as CSSProperties;
    return (
      <span className={`item-art ${size}`} style={{ background: RARITY_ART[item.rarity] }}>
        <img src={src} alt="" hidden onLoad={(e) => setFrames(Math.max(1, Math.round(e.currentTarget.naturalHeight / (e.currentTarget.naturalWidth / 2))))} onError={() => setFailed(true)} />
        <span className="cape-front" style={style} />
      </span>
    );
  }
  return (
    <span className={`item-art ${size}`} style={{ background: RARITY_ART[item.rarity] }}>
      <img className="item-icon" src={src} alt="" onError={() => setFailed(true)} draggable={false} />
    </span>
  );
}

function ItemCard({ item: it, owned, equipped, onOpen }: { item: StoreItem; owned: boolean; equipped: boolean; onOpen: () => void }) {
  return (
    <button className={`item-card ${it.rarity}`} onClick={onOpen}>
      <span className="item-art-wrap">
        <ItemArt item={it} />
        {owned && <span className="owned-flag">{equipped ? "Equipped" : "Owned"}</span>}
      </span>
      <span className="item-info">
        <strong>{it.name}</strong>
        <span className={`rarity ${it.rarity}`}>{cap(it.rarity)}</span>
      </span>
      <span className="item-price">
        {owned ? (
          <span className="muted small">
            <ICheck size={14} /> {it.free ? "Free" : "In your wardrobe"}
          </span>
        ) : it.price !== null ? (
          <>
            <ICoin size={15} /> {it.price.toLocaleString("en-US")}
          </>
        ) : (
          <span className="muted small">Not for sale</span>
        )}
      </span>
    </button>
  );
}

function ItemModal({
  item,
  owned,
  equipped,
  onClose,
  onBought,
  onLoadout,
}: {
  item: StoreItem | null;
  owned: boolean;
  equipped: boolean;
  onClose: () => void;
  onBought: (owned: string[]) => void;
  onLoadout: (l: StoreData["loadout"]) => void;
}) {
  const app = useApp();
  const [busy, setBusy] = useState(false);
  if (!item) return null;
  const price = item.price ?? 0;
  const coins = app.coins ?? 0;
  const short = price - coins;

  const buy = async () => {
    setBusy(true);
    try {
      const r = await api.buy(item.id);
      app.setCoins(balanceOf(r) ?? 0);
      onBought(r.owned);
      app.pushNotification({ kind: "purchase", title: "Purchase complete", detail: `${item.name} is in your wardrobe (${price.toLocaleString("en-US")} coins).`, action: { label: "View", route: { page: "store", tab: "owned" } } });
      app.toast(`${item.name} is yours`, { tone: "success" });
    } catch (e) {
      app.error(e);
    } finally {
      setBusy(false);
    }
  };
  const equip = async () => {
    setBusy(true);
    try {
      const l = await api.setLoadout({ [item.slot]: equipped ? null : item.id });
      onLoadout(l);
      app.toast(equipped ? `Took off ${item.name}` : `Equipped ${item.name}. Others see it next time you play.`);
    } catch (e) {
      app.error(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onClose={onClose} width={640} className="item-modal">
      <div className="item-modal-body">
        <div className="item-preview">
          <ItemArt item={item} size="lg" />
        </div>
        <div className="item-detail">
          <span className={`rarity ${item.rarity}`}>
            {cap(item.rarity)} {SLOT_NAME[item.slot]}
          </span>
          <h2>{item.name}</h2>
          <p>{item.description}</p>
          {owned ? (
            <div className="stack">
              <span className="badge ok">
                <ICheck size={13} /> In your wardrobe
              </span>
              <button className="btn primary" disabled={busy} onClick={equip}>
                {busy && <Spinner />} {equipped ? "Take off" : "Equip"}
              </button>
              <p className="hint">You can also change it in game from the Cosmetics page (Right Shift).</p>
            </div>
          ) : item.price === null ? (
            <p className="muted">This item isn't sold in the store.</p>
          ) : (
            <div className="stack">
              <span className="price lg">
                <ICoin size={20} /> {price.toLocaleString("en-US")}
              </span>
              {short > 0 ? (
                <>
                  <p className="muted small">You need {short.toLocaleString("en-US")} more coins.</p>
                  <button
                    className="btn primary"
                    onClick={() => {
                      onClose();
                      app.navigate({ page: "coins" });
                    }}
                  >
                    <ICoin size={16} /> Get more coins
                  </button>
                </>
              ) : (
                <button className="btn primary" disabled={busy} onClick={buy}>
                  {busy ? <Spinner /> : <ICoin size={16} />} Buy for {price.toLocaleString("en-US")} coins
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}
