import { useEffect, useState } from "react";
import { api } from "../api";
import type { Ping } from "../api/types";

type Result = { ping: Ping } | { error: true };
const cache = new Map<string, { at: number; r: Result }>();
const inflight = new Map<string, Promise<Result>>();
const TTL = 60_000;

function pingOnce(address: string): Promise<Result> {
  const c = cache.get(address);
  if (c && Date.now() - c.at < TTL) return Promise.resolve(c.r);
  let p = inflight.get(address);
  if (!p) {
    p = api
      .ping(address)
      .then((ping): Result => ({ ping }))
      .catch((): Result => ({ error: true }))
      .then((r) => {
        cache.set(address, { at: Date.now(), r });
        inflight.delete(address);
        return r;
      });
    inflight.set(address, p);
  }
  return p;
}

/** Live Server List Ping results for these addresses (cached for a minute). undefined = still pinging. */
export function usePings(addresses: string[]): Record<string, Result | undefined> {
  const key = [...new Set(addresses)].sort().join("|");
  const [out, setOut] = useState<Record<string, Result | undefined>>({});
  useEffect(() => {
    let alive = true;
    const list = key ? key.split("|") : [];
    setOut(Object.fromEntries(list.map((a) => [a, cache.get(a)?.r])));
    for (const a of list) pingOnce(a).then((r) => alive && setOut((o) => ({ ...o, [a]: r })));
    return () => {
      alive = false;
    };
  }, [key]);
  return out;
}

export const pingClass = (ms: number) => (ms < 60 ? "ping good" : ms < 120 ? "ping ok" : "ping bad");
