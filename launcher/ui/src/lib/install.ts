import type { InstallPlan } from "../api/types";

/**
 * Where an install goes when the user doesn't pick: the remembered install target if it still exists,
 * then the active profile, then the first profile.
 */
export function resolveTarget(remembered: string | null, active: string | null, profiles: { id: string }[]): string | null {
  const has = (id: string | null) => !!id && profiles.some((p) => p.id === id);
  if (has(remembered)) return remembered;
  if (has(active)) return active;
  return profiles[0]?.id ?? null;
}

/** Ask before installing when the plan pulls in dependencies the profile doesn't have yet. */
export const needsDependencyConfirm = (plan: InstallPlan) => plan.dependencies.length > 0;

export function dependencyQuestion(title: string, plan: InstallPlan): string {
  const names = plan.dependencies.map((d) => d.title);
  const list = names.length <= 2 ? names.join(" and ") : `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
  return `${title} needs ${list} to work. Install ${names.length === 1 ? "it" : "them"} too?`;
}
