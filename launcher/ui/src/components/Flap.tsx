import type { CSSProperties } from "react";

/** Split-flap letters: each character sits in its own tile and flips in when the text is set. Words wrap whole. */
export function Flap({ text, className }: { text: string; className?: string }) {
  let index = 0;
  return (
    <span className={className ? `flap ${className}` : "flap"} aria-label={text} role="text">
      {text.split(" ").map((word, w) => (
        <span key={w} className="flap-word">
          {Array.from(word).map((ch) => {
            const i = index++;
            return (
              <span key={`${i}-${ch}`} className="flap-cell" style={{ "--i": i } as CSSProperties} aria-hidden>
                {ch}
              </span>
            );
          })}
        </span>
      ))}
    </span>
  );
}
