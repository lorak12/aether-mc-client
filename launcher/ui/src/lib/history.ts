/** Back/forward navigation history: a stack of routes and a cursor into it. */
export interface Hist<R> {
  stack: R[];
  index: number;
}

const LIMIT = 100;
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

export const start = <R>(r: R): Hist<R> => ({ stack: [r], index: 0 });
export const current = <R>(h: Hist<R>): R => h.stack[h.index];
export const canBack = <R>(h: Hist<R>) => h.index > 0;
export const canForward = <R>(h: Hist<R>) => h.index < h.stack.length - 1;

/** Go to `r`: drops anything ahead of the cursor. Navigating to the current route is a no-op. */
export function push<R>(h: Hist<R>, r: R): Hist<R> {
  if (same(current(h), r)) return h;
  const stack = [...h.stack.slice(0, h.index + 1), r].slice(-LIMIT);
  return { stack, index: stack.length - 1 };
}

/** Replace the current entry (e.g. a tab change inside a page) without adding history. */
export function replace<R>(h: Hist<R>, r: R): Hist<R> {
  const stack = [...h.stack];
  stack[h.index] = r;
  return { stack, index: h.index };
}

export const back = <R>(h: Hist<R>): Hist<R> => (canBack(h) ? { ...h, index: h.index - 1 } : h);
export const forward = <R>(h: Hist<R>): Hist<R> => (canForward(h) ? { ...h, index: h.index + 1 } : h);
