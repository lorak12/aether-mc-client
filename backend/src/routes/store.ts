import type { FastifyInstance } from "fastify";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { z } from "zod";
import type { Ctx } from "../app.js";
import { createCheckout, verifyWebhook, type TebexWebhook } from "../tebex.js";

const page = (title: string, text: string) => `<!doctype html><meta charset="utf-8"><title>Aether</title>
<style>body{margin:0;height:100vh;display:grid;place-items:center;background:#0b0d14;color:#e8ebf5;font:16px system-ui,sans-serif}
div{text-align:center;max-width:420px;padding:24px}h1{font-size:22px;margin:0 0 8px}p{color:#9aa3b8;margin:0}</style>
<div><h1>${title}</h1><p>${text}</p></div>`;

export function registerStore(app: FastifyInstance, ctx: Ctx) {
  const { store, now, cfg } = ctx;
  const paymentsOn = () => !!cfg.tebex && cfg.bundles.length > 0;

  // Everything sellable or free, with ownership when signed in.
  app.get("/v1/store", async (req) => {
    const uuid = await ctx.authed(req);
    const owned = uuid ? [...(await store.owned(uuid))] : [];
    const loadout = uuid ? await store.getLoadout(uuid) : {};
    return {
      items: (await ctx.customs.all()).filter((c) => c.free || c.price !== null || owned.includes(c.id)).map((c) => ({ ...c, icon: `/v1/cosmetics/${c.id}/icon` })),
      owned,
      loadout,
      ...(uuid ? await ctx.wallet(uuid) : { coins: null, unlimited: false }),
    };
  });

  // Store art: the model's icon, or the cape texture (the UI crops the front of the cape).
  app.get("/v1/cosmetics/:id/icon", async (req, reply) => {
    const id = (req.params as { id: string }).id;
    const custom = await ctx.customs.get(id);
    if (custom) {
      const b64 = custom.files["icon.png"] ?? custom.files["texture.png"];
      if (!b64) return reply.code(404).send({ error: "not_found" });
      return reply.header("content-type", "image/png").header("cache-control", "public, max-age=300").send(Buffer.from(b64, "base64"));
    }
    const c = await ctx.customs.cosmetic(id);
    if (!c) return reply.code(404).send({ error: "not_found" });
    for (const file of ["icon.png", "texture.png"]) {
      try {
        const png = await readFile(join(cfg.cosmeticsDir, c.asset, file));
        return reply.header("content-type", "image/png").header("cache-control", "public, max-age=86400").header("x-aether-art", file).send(png);
      } catch {
        /* next */
      }
    }
    return reply.code(404).send({ error: "not_found" });
  });

  app.post("/v1/store/buy", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    const b = z.object({ id: z.string() }).safeParse(req.body);
    const item = b.success ? await ctx.customs.cosmetic(b.data.id) : undefined;
    if (!item) return reply.code(404).send({ error: "not_found" });
    if (item.price === null) return reply.code(400).send({ error: item.free ? "already_owned" : "not_for_sale" });
    if (await ctx.unlimited(uuid)) {
      // Admins (src/admins.ts): the item is granted, nothing is charged.
      if ((await store.owned(uuid)).has(item.id)) return reply.code(409).send({ error: "already_owned" });
      await store.grant(uuid, item.id);
      return { ...(await ctx.wallet(uuid)), owned: [...(await store.owned(uuid))] };
    }
    const coins = await store.buy(uuid, item.id, item.price, item.name, now());
    return { coins, unlimited: false, owned: [...(await store.owned(uuid))] };
  });

  app.get("/v1/me/transactions", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    return { transactions: await store.transactions(uuid, 100) };
  });

  // ---- coins through Tebex ----
  app.get("/v1/coins/bundles", async () => ({ enabled: paymentsOn(), bundles: paymentsOn() ? cfg.bundles : [] }));

  app.post("/v1/coins/checkout", async (req, reply) => {
    const uuid = await ctx.user(req, reply);
    if (!uuid) return;
    if (!paymentsOn()) return reply.code(503).send({ error: "payments_disabled" });
    const b = z.object({ bundle: z.string() }).safeParse(req.body);
    const bundle = b.success ? cfg.bundles.find((x) => x.id === b.data.bundle) : undefined;
    if (!bundle) return reply.code(404).send({ error: "not_found" });
    try {
      const c = await createCheckout(cfg.tebex!, bundle.id, uuid, `${cfg.publicUrl}/v1/coins/return`, ctx.fetch);
      await store.addCheckout(c.ident, uuid, bundle.id, now());
      return { url: c.url };
    } catch (e) {
      console.error("tebex checkout failed", e);
      return reply.code(502).send({ error: "checkout_unavailable" });
    }
  });

  app.get("/v1/coins/return", async (req, reply) => {
    const ok = (req.query as { status?: string }).status === "complete";
    return reply
      .type("text/html")
      .send(ok ? page("Thanks for your purchase", "Your coins arrive in a moment. You can close this tab and go back to Aether.") : page("Payment cancelled", "Nothing was charged. You can close this tab."));
  });

  app.post("/v1/webhooks/tebex", async (req, reply) => {
    if (!cfg.tebex) return reply.code(404).send({ error: "not_found" });
    const sig = req.headers["x-signature"];
    if (!verifyWebhook(req.rawBody ?? "", typeof sig === "string" ? sig : undefined, cfg.tebex.webhookSecret)) return reply.code(403).send({ error: "bad_signature" });
    const hook = req.body as TebexWebhook;
    if (hook.type === "validation.webhook") return { id: hook.id };
    const s = hook.subject ?? {};
    const tx = s.transaction_id;
    const uuid = typeof s.custom?.uuid === "string" ? s.custom.uuid : null;
    if (!tx || !uuid) return { ok: true, ignored: true };
    const coins = (s.products ?? []).reduce((sum, p) => {
      const b = cfg.bundles.find((x) => x.id === String(p.id));
      return sum + (b ? (b.coins + b.bonus) * (p.quantity ?? 1) : 0);
    }, 0);
    if (coins <= 0) return { ok: true, ignored: true };
    if (hook.type === "payment.completed") {
      await store.addCoins(uuid, coins, `Bought ${coins.toLocaleString("en-US")} coins`, now(), `tebex:${tx}`);
    } else if (hook.type === "payment.refunded" || hook.type === "payment.dispute.opened") {
      await store.addCoins(uuid, -coins, hook.type === "payment.refunded" ? "Refund" : "Payment disputed", now(), `tebex:${tx}:${hook.type}`);
    }
    return { ok: true };
  });
}
