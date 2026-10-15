import { api } from "../api";
import { useApp } from "../app/store";
import { useAsync } from "../components/hooks";
import { IClose, IInfo, ICheck, IWarning } from "../components/Icons";
import type { Page } from "../api/types";

const ICON = { info: <IInfo size={16} />, success: <ICheck size={16} />, warning: <IWarning size={16} />, danger: <IWarning size={16} /> };

/** Thin announcement strip on Home: sales, offers, outages. Dismissed banners stay hidden. */
export function Banner() {
  const app = useApp();
  const backend = !!app.cfg?.backend;
  const { data } = useAsync(() => (backend ? api.banners() : Promise.resolve([])), [backend]);
  const b = data?.find((x) => !app.prefs.dismissedBanners.includes(x.id));
  if (!b) return null;
  return (
    <div className={`banner ${b.variant}`} role="status">
      <span className="banner-icon">{ICON[b.variant]}</span>
      <span className="banner-text">{b.text}</span>
      {b.link && (
        <button className="banner-link" onClick={() => (b.link!.url ? api.openUrl(b.link!.url).catch(app.error) : app.navigate({ page: (b.link!.page ?? "home") as Page } as never))}>
          {b.link.label}
        </button>
      )}
      <button className="banner-close" aria-label="Dismiss announcement" onClick={() => app.setPrefs({ dismissedBanners: [...app.prefs.dismissedBanners, b.id] })}>
        <IClose size={15} />
      </button>
    </div>
  );
}
