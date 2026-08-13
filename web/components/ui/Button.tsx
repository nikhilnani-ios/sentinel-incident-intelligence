"use client";

import clsx from "clsx";
import type { ButtonHTMLAttributes } from "react";

type Variant = "primary" | "ghost" | "danger";

const VARIANTS: Record<Variant, string> = {
  primary: "bg-trace/15 text-trace border-trace/40 hover:bg-trace/25",
  ghost: "bg-transparent text-muted border-ink-600 hover:text-paper hover:border-ink-600",
  danger: "bg-critical/10 text-critical border-critical/40 hover:bg-critical/20",
};

export function Button({
  variant = "ghost",
  className,
  loading,
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; loading?: boolean }) {
  return (
    <button
      {...props}
      disabled={props.disabled || loading}
      className={clsx(
        "inline-flex items-center gap-2 rounded-sm border px-3 py-1.5 font-mono text-[11px] uppercase tracking-[0.12em] transition-colors disabled:cursor-not-allowed disabled:opacity-40",
        VARIANTS[variant],
        className,
      )}
    >
      {loading && <span className="h-2 w-2 animate-pulse rounded-full bg-current" />}
      {children}
    </button>
  );
}
