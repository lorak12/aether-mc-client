import React from "react";
import { createRoot } from "react-dom/client";
import "@fontsource-variable/inter";
import "@fontsource-variable/sora";
import "./tokens.css";
import "./styles.css";
import { App } from "./App";

async function boot() {
  if (import.meta.env.DEV) await import("./devMock");
  createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
}
boot();
