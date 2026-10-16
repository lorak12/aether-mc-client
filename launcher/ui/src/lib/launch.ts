/** What each `launch-progress` stage means, for the Home progress bar and the launch notification. */
export const STAGES: Record<string, string> = {
  account: "Signing in",
  "kite-client": "Installing the Kite client",
  version: "Fetching version info",
  java: "Downloading Java",
  loader: "Installing the mod loader",
  libraries: "Downloading libraries",
  assets: "Downloading assets",
  "pre-launch": "Running the pre-launch command",
  running: "Starting Minecraft",
};

export const stageText = (stage: string | undefined) => (stage && STAGES[stage]) || "Preparing game files";
