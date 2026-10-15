import { useEffect, useRef, useState } from "react";
import { api } from "../../api";
import type { CoinBundle } from "../../api/types";
import { useApp } from "../../app/store";
import { useAsync } from "../../components/hooks";
import { IArrowLeft, ICheck, ICoin, IExternal } from "../../components/Icons";
import { Modal } from "../../components/overlays";
import { EmptyState, ErrorCard, Skeleton, SkeletonRows, Spinner } from "../../components/ui";
import { ago } from "../../lib/format";
import { SocialUnavailable } from "../../shell/RightSidebar";

export function CoinShop() {
  const app = useApp();
  const enabled = !!app.online?.signed_in;
  const bundles = useAsync(() => (enabled ? api.coinBundles() : Promise.resolve({ enabled: false, bundles: [] as CoinBundle[] })), [enabled]);
  const txns = useAsync(() => (enabled ? api.transactions() : Promise.resolve([])), [enabled]);
  const [buying, setBuying] = useState<CoinBundle | null>(null);

  return (
    <div className="page coin-page">
      <button className="link back-link" onClick={app.back}>
        <IArrowLeft size={15} /> Back
      </button>
      <header className="coin-hero">
        <div>
          <h1>Kite coins</h1>
          <p className="muted">Spend coins on capes, wings, hats and more in the store.</p>
        </div>
        {enabled && (
          <div className="balance">
            <span className="muted small">Your balance</span>
            <strong>
              <ICoin size={26} /> {(app.coins ?? 0).toLocaleString("en-US")}
            </strong>
          </div>
        )}
      </header>

      {!enabled ? (
        <SocialUnavailable />
      ) : (
        <>
          {bundles.error ? (
            <ErrorCard message={bundles.error} retry={bundles.reload} />
          ) : !bundles.data ? (
            <div className="bundle-grid">
              {[0, 1, 2, 3].map((i) => (
                <Skeleton key={i} h={220} r={18} />
              ))}
            </div>
          ) : !bundles.data.enabled ? (
            <EmptyState icon={<ICoin size={24} />} title="Buying coins isn't available yet" text="You can still earn and spend coins you already have. Purchases open once the store's payment provider is set up." />
          ) : (
            <div className="bundle-grid">
              {bundles.data.bundles.map((b, i) => (
                <article key={b.id} className={b.best ? "bundle best" : "bundle"}>
                  {b.best && <span className="bundle-flag">Best value</span>}
                  <div className="coin-stack" aria-hidden>
                    {Array.from({ length: Math.min(i + 1, 5) }, (_, j) => (
                      <span key={j} style={{ transform: `translate(${j * 7}px, ${-j * 6}px)` }}>
                        <ICoin size={30} />
                      </span>
                    ))}
                  </div>
                  <strong className="bundle-coins">{(b.coins + b.bonus).toLocaleString("en-US")}</strong>
                  <span className="muted small">{b.bonus ? `${b.coins.toLocaleString("en-US")} + ${b.bonus.toLocaleString("en-US")} bonus` : "coins"}</span>
                  <button className={b.best ? "btn primary" : "btn"} onClick={() => setBuying(b)}>
                    {b.price}
                  </button>
                </article>
              ))}
            </div>
          )}

          <section className="stack">
            <h2 className="section-title">History</h2>
            {txns.error ? (
              <ErrorCard message={txns.error} retry={txns.reload} />
            ) : !txns.data ? (
              <SkeletonRows n={3} h={44} />
            ) : txns.data.length === 0 ? (
              <p className="muted">No coin activity yet.</p>
            ) : (
              <ul className="txn-list">
                {txns.data.map((t) => (
                  <li key={t.id}>
                    <span>{t.label}</span>
                    <span className="muted small">{ago(t.at)}</span>
                    <span className={t.coins > 0 ? "txn plus" : "txn"}>
                      {t.coins > 0 ? "+" : ""}
                      {t.coins.toLocaleString("en-US")}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}

      <Checkout bundle={buying} onClose={() => setBuying(null)} onDone={txns.reload} />
    </div>
  );
}

/** Opens Tebex checkout in the browser, then waits for the payment webhook to credit the coins. */
function Checkout({ bundle, onClose, onDone }: { bundle: CoinBundle | null; onClose: () => void; onDone: () => void }) {
  const app = useApp();
  const [state, setState] = useState<"confirm" | "opening" | "waiting" | "done">("confirm");
  const start = useRef<number>(0);
  const close = () => {
    setState("confirm");
    onClose();
  };

  // While waiting, check the balance every 5 s for up to 20 minutes.
  useEffect(() => {
    if (state !== "waiting") return;
    const t0 = Date.now();
    const t = setInterval(async () => {
      try {
        const me = await api.me();
        if (me.coins > start.current) {
          app.setCoins(me.coins);
          setState("done");
          onDone();
          app.pushNotification({ kind: "purchase", title: `${(me.coins - start.current).toLocaleString("en-US")} coins added`, detail: `Your balance is ${me.coins.toLocaleString("en-US")}.` });
        }
      } catch {
        /* keep waiting */
      }
      if (Date.now() - t0 > 20 * 60_000) clearInterval(t);
    }, 5000);
    return () => clearInterval(t);
  }, [state, app, onDone]);

  if (!bundle) return null;
  const pay = async () => {
    setState("opening");
    try {
      start.current = app.coins ?? 0;
      const url = await api.checkout(bundle.id);
      await api.openUrl(url);
      setState("waiting");
    } catch (e) {
      app.error(e);
      setState("confirm");
    }
  };
  const total = bundle.coins + bundle.bonus;
  return (
    <Modal open onClose={close} title={state === "done" ? "Coins added" : "Buy coins"} width={440}>
      {state === "done" ? (
        <div className="stack center-text">
          <span className="ok-dot lg">
            <ICheck size={22} />
          </span>
          <p>Thanks! Your balance is {(app.coins ?? 0).toLocaleString("en-US")} coins.</p>
          <button className="btn primary" onClick={close}>
            Done
          </button>
        </div>
      ) : state === "waiting" ? (
        <div className="stack center-text">
          <Spinner size={22} />
          <p>Finish paying in your browser. Your coins appear here as soon as the payment goes through.</p>
          <button className="btn ghost" onClick={close}>
            Close, I'll check later
          </button>
        </div>
      ) : (
        <div className="stack">
          <div className="checkout-line">
            <span>
              <ICoin size={18} /> {total.toLocaleString("en-US")} coins
            </span>
            <strong>{bundle.price}</strong>
          </div>
          <p className="muted small">Payment is handled by Tebex in your browser. Kite Client never sees your card details.</p>
          <button className="btn primary" disabled={state === "opening"} onClick={pay}>
            {state === "opening" ? <Spinner /> : <IExternal size={15} />} Continue to payment
          </button>
        </div>
      )}
    </Modal>
  );
}
