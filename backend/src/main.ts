import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";
import { mojangVerifier } from "./mojang.js";
import { MemoryStore } from "./store.js";

const cfg = loadConfig();
const app = buildApp(cfg, new MemoryStore(), mojangVerifier);
app.listen({ port: cfg.port, host: "0.0.0.0" }).then(() => console.log(`aether-backend on :${cfg.port}`));
