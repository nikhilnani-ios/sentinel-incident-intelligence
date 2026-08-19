"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import clsx from "clsx";
import { clearSession, getRole, getToken } from "@/lib/auth";
import { useIncidentStream } from "@/lib/useIncidentStream";
import { showcaseEnabled } from "@/lib/showcase";

const NAV = [
  { href: "/", label: "Overview" },
  { href: "/incidents", label: "Incidents" },
  { href: "/services", label: "Services" },
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [role, setRole] = useState<string>("VIEWER");
  const [menuOpen, setMenuOpen] = useState(false);
  const { connected } = useIncidentStream();

  useEffect(() => {
    if (!getToken() && pathname !== "/login") {
      router.replace("/login");
      return;
    }
    setRole(getRole());
  }, [pathname, router]);

  if (pathname === "/login") {
    return <>{children}</>;
  }

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-20 border-b border-ink-700 bg-ink-900/85 backdrop-blur">
        <div className="mx-auto max-w-[1400px] px-4 py-3 sm:px-6">
          <div className="flex items-center gap-4 lg:gap-8">
          <Link href="/" className="flex items-baseline gap-2">
            <span className="font-display text-lg tracking-tightest text-paper">SENTINEL</span>
            <span className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted">reliability</span>
          </Link>

          <nav className="hidden gap-1 md:flex">
            {NAV.map((item) => {
              const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={clsx(
                    "rounded-sm px-3 py-1.5 font-mono text-[11px] uppercase tracking-[0.12em] transition-colors",
                    active ? "bg-ink-700 text-paper" : "text-muted hover:text-paper",
                  )}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>

          <div className="ml-auto flex items-center gap-3 sm:gap-4">
            {showcaseEnabled && (
              <span className="hidden border border-medium/40 bg-medium/10 px-2 py-1 font-mono text-[9px] uppercase tracking-[0.12em] text-medium lg:inline">
                Portfolio showcase
              </span>
            )}
            <span className="flex items-center gap-1.5 font-mono text-[10px] uppercase tracking-[0.12em] text-muted">
              <span className="relative flex h-2 w-2">
                {connected && (
                  <span className="absolute inline-flex h-full w-full animate-pulse-ring rounded-full bg-ok" />
                )}
                <span
                  className={clsx("relative inline-flex h-2 w-2 rounded-full", connected ? "bg-ok" : "bg-muted")}
                />
              </span>
              {connected ? "live" : "reconnecting"}
            </span>
            <span className="hidden font-mono text-[10px] uppercase tracking-[0.12em] text-muted sm:inline">{role}</span>
            <button
              onClick={() => {
                clearSession();
                router.replace("/login");
              }}
              className="hidden font-mono text-[10px] uppercase tracking-[0.12em] text-muted hover:text-paper md:block"
            >
              Sign out
            </button>
            <button
              type="button"
              aria-label="Toggle navigation"
              aria-expanded={menuOpen}
              onClick={() => setMenuOpen((open) => !open)}
              className="grid h-10 w-10 place-items-center rounded-sm border border-ink-700 text-paper md:hidden"
            >
              <span className="font-mono text-lg leading-none">{menuOpen ? "×" : "☰"}</span>
            </button>
          </div>
          </div>
          {menuOpen && (
            <div className="mt-3 border-t border-ink-700 pt-3 md:hidden">
              <nav className="grid gap-1">
                {NAV.map((item) => {
                  const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
                  return (
                    <Link key={item.href} href={item.href} onClick={() => setMenuOpen(false)} className={clsx(
                      "rounded-sm px-3 py-2.5 font-mono text-xs uppercase tracking-[0.12em]",
                      active ? "bg-ink-700 text-paper" : "text-muted",
                    )}>
                      {item.label}
                    </Link>
                  );
                })}
              </nav>
              <div className="mt-2 flex items-center justify-between border-t border-ink-700 px-3 pt-3">
                <span className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted">{role}</span>
                <button onClick={() => { clearSession(); router.replace("/login"); }} className="min-h-10 font-mono text-[10px] uppercase tracking-[0.12em] text-muted">
                  Sign out
                </button>
              </div>
            </div>
          )}
        </div>
      </header>

      <main className="mx-auto max-w-[1400px] px-4 py-5 sm:px-6 sm:py-6">{children}</main>
    </div>
  );
}
