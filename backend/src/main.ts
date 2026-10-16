import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";
import { mojangVerifier } from "./mojang.js";
import { SqliteStore } from "./store.js";

const cfg = loadConfig();
const store = new SqliteStore(cfg.dbPath);
const app = buildApp(cfg, store, mojangVerifier);
app.listen({ port: cfg.port, host: "0.0.0.0" }).then(() => {
  console.log(`kite-backend on :${cfg.port} (database ${cfg.dbPath})`);
  if (cfg.allowOffline) console.warn("KITE_ALLOW_OFFLINE is on: offline accounts can sign in. Development only.");
  if (!cfg.tebex) console.log("Tebex not configured: buying coins is off.");
});
