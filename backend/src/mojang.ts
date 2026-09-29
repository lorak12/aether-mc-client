/** Proves the caller owns a Minecraft account: the client first calls Mojang's `join` with our serverId,
 *  then we ask Mojang's sessionserver whether that username joined that serverId. */
export interface Identity {
  uuid: string;
  name: string;
}
export type Verifier = (username: string, serverId: string) => Promise<Identity | null>;

export const mojangVerifier: Verifier = async (username, serverId) => {
  const url = new URL("https://sessionserver.mojang.com/session/minecraft/hasJoined");
  url.searchParams.set("username", username);
  url.searchParams.set("serverId", serverId);
  const r = await fetch(url);
  if (r.status !== 200) return null;
  const j = (await r.json()) as { id?: string; name?: string };
  return j.id && j.name ? { uuid: j.id, name: j.name } : null;
};

export const normUuid = (u: string) => u.replaceAll("-", "").toLowerCase();
