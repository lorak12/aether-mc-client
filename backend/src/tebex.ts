import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import type { Tebex } from "./config.js";

const HEADLESS = "https://headless.tebex.io/api";

/** Tebex signs webhooks with HMAC-SHA256(secret, hex SHA-256 of the raw body) in `X-Signature`. */
export function verifyWebhook(rawBody: string, signature: string | undefined, secret: string): boolean {
  if (!signature) return false;
  const bodyHash = createHash("sha256").update(rawBody, "utf8").digest("hex");
  const expected = createHmac("sha256", secret).update(bodyHash).digest("hex");
  const a = Buffer.from(expected, "utf8");
  const b = Buffer.from(signature.trim(), "utf8");
  return a.length === b.length && timingSafeEqual(a, b);
}

/** Webhook body, the parts we use. */
export interface TebexWebhook {
  id: string;
  type: string;
  subject?: {
    transaction_id?: string;
    custom?: Record<string, unknown> | null;
    products?: { id: number | string; quantity?: number }[];
    status?: { id?: number; description?: string };
  };
}

export type Fetch = typeof fetch;

/**
 * Create a Headless API basket with one coin package and return its checkout link. `custom.uuid` ties the payment to
 * the player; the webhook reads it back. Needs a Tebex store without username sign-in (Headless "custom" store).
 */
export async function createCheckout(t: Tebex, packageId: string, uuid: string, returnUrl: string, f: Fetch = fetch): Promise<{ ident: string; url: string }> {
  const r = await f(`${HEADLESS}/accounts/${encodeURIComponent(t.publicToken)}/baskets`, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({ complete_url: `${returnUrl}?status=complete`, cancel_url: `${returnUrl}?status=cancelled`, complete_auto_redirect: true, custom: { uuid } }),
  });
  if (!r.ok) throw new Error(`tebex basket ${r.status}`);
  const basket = ((await r.json()) as { data: { ident: string } }).data;
  const add = await f(`${HEADLESS}/baskets/${encodeURIComponent(basket.ident)}/packages`, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({ package_id: packageId, quantity: 1 }),
  });
  if (!add.ok) throw new Error(`tebex package ${add.status}`);
  const data = ((await add.json()) as { data: { ident: string; links?: { checkout?: string } } }).data;
  const url = data.links?.checkout;
  if (!url) throw new Error("tebex sent no checkout link");
  return { ident: basket.ident, url };
}
