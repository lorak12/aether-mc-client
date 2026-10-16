import { useEffect } from "react";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { AppProvider, useApp } from "./app/store";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { CommunityPage } from "./pages/community/Community";
import { ContentPage } from "./pages/content/Content";
import { ProjectPage } from "./pages/content/ProjectPage";
import { ExplorePage } from "./pages/explore/Explore";
import { Home } from "./pages/home/Home";
import { ArticlePage, NewsPage } from "./pages/news/News";
import { NewProfileModal } from "./pages/profiles/NewProfileModal";
import { ProfilesPage } from "./pages/profiles/Profiles";
import { SettingsPage } from "./pages/settings";
import { CoinShop } from "./pages/store/CoinShop";
import { StorePage } from "./pages/store/Store";
import { LogsWindow } from "./shell/LogsWindow";
import { FriendsPanel } from "./shell/RightSidebar";
import { SearchOverlay } from "./shell/SearchOverlay";
import { Sidebar } from "./shell/Sidebar";
import { Toasts } from "./shell/Toasts";
import { TopBar } from "./shell/TopBar";

function isLogsWindow() {
  try {
    if (getCurrentWindow().label === "logs") return true;
  } catch {
    /* plain browser */
  }
  return new URLSearchParams(location.search).get("view") === "logs";
}

export function App() {
  // The live log window loads the same bundle; it is told apart by its window label (or ?view=logs in a plain browser).
  if (isLogsWindow()) return <ErrorBoundary><LogsWindow /></ErrorBoundary>;
  return (
    <ErrorBoundary>
      <AppProvider>
        <Shell />
      </AppProvider>
    </ErrorBoundary>
  );
}

function Shell() {
  const app = useApp();
  const r = app.route;

  // Mouse back/forward buttons.
  useEffect(() => {
    const h = (e: MouseEvent) => {
      if (e.button === 3) (e.preventDefault(), app.back());
      if (e.button === 4) (e.preventDefault(), app.forward());
    };
    window.addEventListener("mouseup", h);
    return () => window.removeEventListener("mouseup", h);
  }, [app.back, app.forward]);

  const classes = ["shell", app.prefs.sidebarCollapsed && "side-collapsed", app.prefs.reduceMotion && "reduce-motion"].filter(Boolean).join(" ");
  const right = (r.page === "home" && app.prefs.showFriendsOnHome) || r.page === "community";

  return (
    <div className={classes}>
      <div className="aurora" aria-hidden />
      <TopBar />
      <div className="body">
        <Sidebar />
        <main className={right ? "main with-right" : "main"} key={r.page + ("id" in r ? r.id : "")}>
          <div className="main-scroll">
            <ErrorBoundary key={r.page}>
            {r.page === "home" && <Home />}
            {r.page === "profiles" && <ProfilesPage open={r.open} />}
            {r.page === "content" && <ContentPage kind={r.kind} q={r.q} />}
            {r.page === "project" && <ProjectPage id={r.id} kind={r.kind} provider={r.provider} />}
            {r.page === "explore" && <ExplorePage />}
            {r.page === "community" && <CommunityPage tab={r.tab} friend={r.friend} />}
            {r.page === "news" && <NewsPage />}
            {r.page === "article" && <ArticlePage id={r.id} />}
            {r.page === "store" && <StorePage tab={r.tab} />}
            {r.page === "coins" && <CoinShop />}
            {r.page === "settings" && <SettingsPage tab={r.tab} />}
            </ErrorBoundary>
          </div>
          {r.page === "home" && right && <FriendsPanel />}
          {r.page === "community" && <FriendsPanel selected={r.friend} onOpen={(f) => app.replace({ page: "community", tab: "messages", friend: f.id })} />}
        </main>
      </div>
      <SearchOverlay />
      <NewProfileModal />
      <Toasts />
    </div>
  );
}
