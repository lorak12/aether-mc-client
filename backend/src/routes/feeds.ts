import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { Ctx } from "../app.js";

const url = z.string().url().refine((u) => u.startsWith("https://") || u.startsWith("http://"), "web links only");

export const NewsItem = z.object({
  id: z.string().min(1).max(64),
  tag: z.enum(["Update", "Event", "Sale", "Community"]),
  title: z.string().min(1).max(140),
  excerpt: z.string().max(400).default(""),
  /** Paragraphs. */
  body: z.array(z.string().max(4000)).max(50).default([]),
  /** Picture URL; `art` (a CSS gradient) is used when there's none. */
  image: url.optional(),
  art: z.string().max(300).optional(),
  /** Unix milliseconds. */
  date: z.number().int(),
  size: z.enum(["lg", "wide", "sm"]).default("sm"),
  link: url.optional(),
});

export const BannerItem = z.object({
  id: z.string().min(1).max(64),
  variant: z.enum(["info", "success", "warning", "danger"]),
  text: z.string().min(1).max(200),
  /** A launcher page (`page`) or a web link (`url`). */
  link: z.object({ label: z.string().max(40), page: z.string().max(40).optional(), url: url.optional() }).optional(),
});

export const ServerItem = z.object({
  id: z.string().min(1).max(64),
  name: z.string().min(1).max(60),
  address: z.string().min(1).max(255),
  description: z.string().max(300).default(""),
  region: z.enum(["EU", "NA", "Asia", "OCE"]),
  genres: z.array(z.string().max(30)).max(10).default([]),
  partner: z.boolean().default(false),
  /** Accent colour for the card. */
  color: z.string().max(40).optional(),
  /** Recommended game version. */
  version: z.string().max(20).optional(),
});

const FEEDS = { news: z.array(NewsItem).max(200), banners: z.array(BannerItem).max(20), servers: z.array(ServerItem).max(200) } as const;
type Feed = keyof typeof FEEDS;

export function registerFeeds(app: FastifyInstance, ctx: Ctx) {
  const read = async (k: Feed) => {
    const v = await ctx.store.getKv(`feed:${k}`);
    return v ? (JSON.parse(v) as unknown[]) : [];
  };

  app.get("/v1/news", async () => ({ news: ((await read("news")) as { date: number }[]).sort((a, b) => b.date - a.date) }));
  app.get("/v1/banners", async () => ({ banners: await read("banners") }));
  app.get("/v1/servers", async () => ({ servers: await read("servers") }));

  // Replace a whole feed: PUT /v1/admin/feeds/news with the array.
  app.put("/v1/admin/feeds/:feed", async (req, reply) => {
    if (!ctx.admin(req)) return reply.code(401).send({ error: "unauthorized" });
    const feed = (req.params as { feed: string }).feed as Feed;
    const schema = FEEDS[feed];
    if (!schema) return reply.code(404).send({ error: "not_found" });
    const parsed = schema.safeParse(req.body);
    if (!parsed.success) return reply.code(400).send({ error: "bad_request", issues: parsed.error.issues.slice(0, 5).map((i) => `${i.path.join(".")}: ${i.message}`) });
    await ctx.store.setKv(`feed:${feed}`, JSON.stringify(parsed.data));
    return { ok: true, count: parsed.data.length };
  });
}
