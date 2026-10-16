import { useState } from "react";
import { api } from "../../api";
import type { NewsTag } from "../../api/types";
import { useApp } from "../../app/store";
import { useAsync } from "../../components/hooks";
import { IArrowLeft, IExternal, INews } from "../../components/Icons";
import { EmptyState, ErrorCard, Skeleton } from "../../components/ui";
import { newsBackground } from "../../lib/art";
import { date } from "../../lib/format";

const TAGS: ("All" | NewsTag)[] = ["All", "Update", "Event", "Sale", "Community"];

function useNews() {
  const app = useApp();
  const backend = !!app.cfg?.backend;
  return useAsync(() => (backend ? api.news() : Promise.resolve([])), [backend]);
}

export function NewsPage() {
  const app = useApp();
  const news = useNews();
  const [tag, setTag] = useState<(typeof TAGS)[number]>("All");
  const list = (news.data ?? []).filter((n) => tag === "All" || n.tag === tag);

  return (
    <div className="page">
      <header className="page-head">
        <h1>News</h1>
        <div className="tag-filter" role="radiogroup" aria-label="Filter by tag">
          {TAGS.map((t) => (
            <button key={t} role="radio" aria-checked={t === tag} className={t === tag ? "on" : ""} onClick={() => setTag(t)}>
              {t}
            </button>
          ))}
        </div>
      </header>
      {news.error ? (
        <ErrorCard message={news.error} retry={news.reload} />
      ) : !news.data ? (
        <div className="bento news">
          <Skeleton h={340} r={18} style={{ gridColumn: "span 2", gridRow: "span 2" }} />
          <Skeleton h={160} r={18} style={{ gridColumn: "span 2" }} />
          <Skeleton h={160} r={18} />
          <Skeleton h={160} r={18} />
        </div>
      ) : list.length === 0 ? (
        <EmptyState
          icon={<INews size={22} />}
          title={!app.cfg?.backend ? "No news here" : tag === "All" ? "No news yet" : `No ${tag.toLowerCase()} posts`}
          text={!app.cfg?.backend ? "Updates and announcements show up once the launcher is connected to the Kite service." : "Check back soon."}
        />
      ) : (
        <div className="bento news">
          {list.map((n, i) => {
            // Without a filter the first post is featured; filtered lists keep the authored sizes.
            const size = tag === "All" ? n.size : i === 0 ? "lg" : "sm";
            return (
              <button key={n.id} className={`bento-card ${size}`} style={{ background: newsBackground(n) }} onClick={() => app.navigate({ page: "article", id: n.id })}>
                <span className={`tag ${n.tag.toLowerCase()}`}>{n.tag}</span>
                <span className="bento-text">
                  <strong>{n.title}</strong>
                  {size !== "sm" && <span className="bento-excerpt">{n.excerpt}</span>}
                  <span className="bento-date">{date(n.date)}</span>
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

export function ArticlePage({ id }: { id: string }) {
  const app = useApp();
  const news = useNews();
  const a = news.data?.find((n) => n.id === id);
  if (news.error) return <div className="page"><ErrorCard message={news.error} retry={news.reload} /></div>;
  if (!news.data) return <div className="page"><Skeleton h={280} r={18} /></div>;
  if (!a)
    return (
      <div className="page">
        <EmptyState title="This post was removed" action={<button className="btn" onClick={() => app.navigate({ page: "news" })}>Back to news</button>} />
      </div>
    );
  const more = news.data.filter((n) => n.id !== id).slice(0, 3);
  return (
    <div className="page article">
      <button className="link back-link" onClick={app.back}>
        <IArrowLeft size={15} /> Back
      </button>
      <header className="article-hero" style={{ background: newsBackground(a) }}>
        <span className={`tag ${a.tag.toLowerCase()}`}>{a.tag}</span>
        <h1>{a.title}</h1>
        <span className="article-date">{date(a.date)}</span>
      </header>
      <div className="article-body">
        <p className="lead">{a.excerpt}</p>
        {a.body.map((p, i) => (
          <p key={i}>{p}</p>
        ))}
        {a.link && (
          <button className="btn" onClick={() => api.openUrl(a.link!).catch(app.error)}>
            Read more <IExternal size={13} />
          </button>
        )}
      </div>
      {more.length > 0 && <section className="stack">
        <h2 className="section-title">More news</h2>
        <div className="more-news">
          {more.map((n) => (
            <button key={n.id} className="bento-card sm" style={{ background: newsBackground(n) }} onClick={() => app.navigate({ page: "article", id: n.id })}>
              <span className={`tag ${n.tag.toLowerCase()}`}>{n.tag}</span>
              <span className="bento-text">
                <strong>{n.title}</strong>
                <span className="bento-date">{date(n.date)}</span>
              </span>
            </button>
          ))}
        </div>
      </section>}
    </div>
  );
}
