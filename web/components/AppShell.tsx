"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import clsx from "clsx";
import { clearSession, getRole, getToken } from "@/lib/auth";
import { useIncidentStream } from "@/lib/useIncidentStream";

const NAV = [
  { href: "/", label: "Overview" },
  { href: "/incidents", label: "Incidents" },
  { href: "/services", label: "Services" },
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [role, setRole] = useState<string>("VIEWER");
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
        <div className="mx-auto flex max-w-[1400px] items-center gap-8 px-6 py-3">
          <Link href="/" className="flex items-baseline gap-2">
            <span className="font-display text-lg tracking-tightest text-paper">SENTINEL</span>
            <span className="font-mono text-[10px] uppercase tracking-[0.18em] text-muted">reliability</span>
          </Link>

          <nav className="flex gap-1">
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

          <div className="ml-auto flex items-center gap-4">
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
            <span className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted">{role}</span>
            <button
              onClick={() => {
                clearSession();
                router.replace("/login");
              }}
              className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted hover:text-paper"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-[1400px] px-6 py-6">{children}</main>
    </div>
  );
}
