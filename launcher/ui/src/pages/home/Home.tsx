import { useRef, useState } from "react";
import { api } from "../../api";
import { useApp } from "../../app/store";
import { useAsync } from "../../components/hooks";
import { IChevronDown, IPlay, IPlus, ILogs, IClose, IProfiles, IStop, IFile, IFolder, IUpload } from "../../components/Icons";
import { Popout } from "../../components/Menu";
import { Cover, ErrorCard, Skeleton, Spinner } from "../../components/ui";
import { coverOf, newsBackground } from "../../lib/art";
import { date } from "../../lib/format";
import { stageText } from "../../lib/launch";
import { profileLabel } from "../../lib/versions";
import { AccountMenu } from "../../shell/Accounts";
import { UploadLogsModal } from "../../shell/AppMenu";
import { Banner } from "../../shell/Banner";

export function Home() {
  const app = useApp();
  const nameRef = useRef<HTMLButtonElement>(null);
  const pickRef = useRef<HTMLButtonElement>(null);
  const [acctOpen, setAcctOpen] = useState(false);
  const [pickOpen, setPickOpen] = useState(false);
  const [sharing, setSharing] = useState(false);
  const backend = !!app.cfg?.backend;
  const online = useAsync(() => (backend ? api.onlineCount() : Promise.resolve(null)), [backend]);
  const news = useAsync(() => (backend ? api.news() : Promise.resolve([])), [backend]);

  const p = app.active;
  const L = app.launch;
  const busy = L.phase === "preparing" || L.phase === "running";
  const progress = L.phase === "preparing" ? L.progress : null;
  const pct = progress && progress.total > 0 ? Math.round((progress.done / progress.total) * 100) : 0;
  const signedIn = !!app.account;
  const name = app.account?.username || "player";
  const runningOther = busy && "profileId" in L && L.profileId !== p?.id ? app.profiles.find((x) => x.id === L.profileId) : null;

  return (
    <div className="home">
      <Banner />
      <div className="greet-row">
        <h1 className="greet">
          Welcome back,{" "}
          <button ref={nameRef} className="greet-name" onClick={() => setAcctOpen((o) => !o)} aria-haspopup="menu">
            {name}
            <IChevronDown size={18} />
          </button>
        </h1>
        {backend && (
          <div className="online-count" aria-live="polite">
            <span className="live-dot" />
            {online.data != null ? (
              <span>
                <strong>{online.data.toLocaleString("en-US")}</strong> {online.data === 1 ? "player" : "players"} online
              </span>
            ) : online.error ? (
              <span className="muted">Online count unavailable</span>
            ) : (
              <Skeleton w={130} h={16} />
            )}
          </div>
        )}
      </div>
      <AccountMenu open={acctOpen} onClose={() => setAcctOpen(false)} anchor={nameRef} align="start" />

      <section className="launch-stage">
        <div className="launch-center">
          {!app.profilesLoaded ? (
            <Spinner size={28} />
          ) : !p ? (
            <>
              <button className="launch-btn" onClick={() => app.setNewProfileOpen(true)}>
                <IPlus size={22} />
                <span>Create a profile</span>
              </button>
              <p className="launch-sub">Pick a Minecraft version to start playing.</p>
            </>
          ) : (
            <>
              <div className="launch-row">
                <div className={`launch-split ${L.phase}`}>
                  <button
                    className={`launch-btn ${L.phase}`}
                    disabled={busy || !signedIn}
                    title={signedIn ? undefined : "Add an account first"}
                    onClick={() => app.play()}
                  >
                    {L.phase === "preparing" ? <Spinner size={22} /> : L.phase === "running" ? <span className="live-dot" /> : <IPlay size={22} />}
                    <span>{L.phase === "preparing" ? "Preparing" : L.phase === "running" ? "Playing" : "Launch Game"}</span>
                  </button>
                  <span className="launch-divider" aria-hidden />
                  <button
                    ref={pickRef}
                    className="launch-caret"
                    onClick={() => setPickOpen((o) => !o)}
                    disabled={busy}
                    aria-haspopup="listbox"
                    aria-label="Choose profile"
                    title={runningOther ? `${runningOther.name} is running` : "Choose profile"}
                  >
                    <IChevronDown size={18} />
                  </button>
                </div>
                {busy && (
                  <button className="launch-stop" onClick={app.stop} title={L.phase === "preparing" ? "Cancel the launch" : "Stop the game"} aria-label={L.phase === "preparing" ? "Cancel launch" : "Stop the game"}>
                    <IStop size={18} />
                  </button>
                )}
              </div>
              <p className="launch-profile-name">{profileLabel(p)}</p>
              {!signedIn && app.accountsLoaded && (
                <button className="link" onClick={() => app.setOverlay("account")}>
                  Add an account to play
                </button>
              )}
              {L.phase === "preparing" && (
                <div className="launch-progress">
                  <div className="row between small">
                    <span>{stageText(progress?.stage ?? "account")}</span>
                    {progress && progress.total > 1 && (
                      <span className="muted">
                        {progress.done.toLocaleString("en-US")} / {progress.total.toLocaleString("en-US")}
                      </span>
                    )}
                  </div>
                  <div className="progress">
                    <div style={{ width: `${progress ? Math.max(pct, 4) : 4}%` }} />
                  </div>
                </div>
              )}
              {L.phase === "running" && (
                <button className="btn sm glass" onClick={() => api.openLogsWindow().catch(app.error)}>
                  <ILogs size={15} /> Live logs
                </button>
              )}
            </>
          )}
        </div>
        {L.phase === "crashed" && (
          <div className="crash-card" role="alert">
            <div className="row between">
              <strong>Minecraft closed with exit code {L.code ?? "unknown"}</strong>
              <button className="icon-btn sm" onClick={app.dismissCrash} aria-label="Dismiss">
                <IClose size={15} />
              </button>
            </div>
            <pre className="log">{L.log || "No output was captured."}</pre>
            <div className="row wrap">
              {L.crashReport && (
                <button className="btn sm" onClick={() => api.revealPath(L.crashReport!).catch(app.error)}>
                  <IFile size={14} /> Crash report
                </button>
              )}
              {L.logPath && (
                <button className="btn sm" onClick={() => api.revealPath(L.logPath!).catch(app.error)}>
                  <IFile size={14} /> Full log
                </button>
              )}
              <button className="btn sm" onClick={() => api.openPath("client-logs", L.profileId).catch(app.error)}>
                <IFolder size={14} /> Logs folder
              </button>
              <button className="btn sm" onClick={() => setSharing(true)}>
                <IUpload size={14} /> Share logs
              </button>
            </div>
          </div>
        )}
      </section>
      <UploadLogsModal open={sharing} onClose={() => setSharing(false)} profileId={L.phase === "crashed" ? L.profileId : null} />

      <Popout open={pickOpen} onClose={() => setPickOpen(false)} anchor={pickRef} className="profile-pick" width={320}>
        <div className="menu-heading">Play</div>
        <div className="pick-list">
          {app.profiles.map((x) => (
            <button
              key={x.id}
              className={x.id === app.activeId ? "pick on" : "pick"}
              onClick={() => {
                app.setActive(x.id);
                setPickOpen(false);
              }}
            >
              <Cover cover={coverOf(x)} seed={x.id} loader={x.loader} className="pick-cover" />
              <span className="pick-text">
                <strong>{x.name}</strong>
                <span className="muted small">{profileLabel(x)}</span>
              </span>
            </button>
          ))}
        </div>
        <button
          className="menu-item"
          onClick={() => {
            setPickOpen(false);
            app.navigate({ page: "profiles" });
          }}
        >
          <span className="menu-icon">
            <IProfiles size={16} />
          </span>
          <span className="menu-label">All profiles</span>
        </button>
      </Popout>

      {backend && (news.error || !news.data || news.data.length > 0) && (
        <section className="announce">
          <div className="section-head">
            <h2>Announcements</h2>
            <button className="link" onClick={() => app.navigate({ page: "news" })}>
              All news
            </button>
          </div>
          {news.error ? (
            <ErrorCard message={news.error} retry={news.reload} />
          ) : !news.data ? (
            <div className="bento">
              <Skeleton h={260} r={18} style={{ gridColumn: "span 2", gridRow: "span 2" }} />
              <Skeleton h={124} r={18} style={{ gridColumn: "span 2" }} />
              <Skeleton h={124} r={18} />
              <Skeleton h={124} r={18} />
            </div>
          ) : (
            <div className="bento">
              {news.data.slice(0, 4).map((n, i) => (
                <button key={n.id} className={`bento-card ${i === 0 ? "lg" : i === 1 ? "wide" : "sm"}`} style={{ background: newsBackground(n) }} onClick={() => app.navigate({ page: "article", id: n.id })}>
                  <span className={`tag ${n.tag.toLowerCase()}`}>{n.tag}</span>
                  <span className="bento-text">
                    <strong>{n.title}</strong>
                    {i === 0 && <span className="bento-excerpt">{n.excerpt}</span>}
                    <span className="bento-date">{date(n.date)}</span>
                  </span>
                </button>
              ))}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
