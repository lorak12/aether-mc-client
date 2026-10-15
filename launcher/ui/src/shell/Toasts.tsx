import { useApp } from "../app/store";
import { IClose } from "../components/Icons";

export function Toasts() {
  const app = useApp();
  return (
    <div className="toasts" aria-live="polite">
      {app.toasts.map((t) => (
        <div key={t.id} className={t.tone ? `toast ${t.tone}` : "toast"}>
          <span>{t.text}</span>
          {t.action && (
            <button
              className="link sm"
              onClick={() => {
                t.action!.run();
                app.dismissToast(t.id);
              }}
            >
              {t.action.label}
            </button>
          )}
          <button className="icon-btn sm" aria-label="Dismiss" onClick={() => app.dismissToast(t.id)}>
            <IClose size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}
