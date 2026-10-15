/**
 * Accounts with unlimited coins: buying in the store grants the item without charging. Matched by Minecraft name,
 * ignoring case. Names are Mojang-verified on /v1/auth/mojang; offline (dev) sign-ins can pick any name, which is why
 * AETHER_ALLOW_OFFLINE is refused in production. More names can be added with AETHER_ADMINS="name1,name2".
 */
export const ADMINS: readonly string[] = ["nakii14"];

/** What /v1/me, /v1/store and /v1/store/buy report as the balance of an admin (JSON has no Infinity). */
export const UNLIMITED_COINS = 999_999_999;

export function isAdmin(name: string | null | undefined, extra: readonly string[] = []): boolean {
  if (!name) return false;
  const n = name.toLowerCase();
  return ADMINS.some((a) => a.toLowerCase() === n) || extra.some((a) => a.toLowerCase() === n);
}
