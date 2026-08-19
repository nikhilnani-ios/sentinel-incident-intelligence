"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { api } from "@/lib/api";
import { setSession } from "@/lib/auth";
import type { Role } from "@/lib/types";
import { showcaseEnabled } from "@/lib/showcase";

const ROLES: { role: Role; description: string }[] = [
  { role: "VIEWER", description: "Read incidents, dashboards and topology" },
  { role: "RESPONDER", description: "Acknowledge, comment and request analysis" },
  { role: "COMMANDER", description: "Resolve incidents and declare duplicates" },
  { role: "ADMIN", description: "Edit the service catalog and dependencies" },
];

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("sre@acme.io");
  const [role, setRole] = useState<Role>("COMMANDER");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function signIn() {
    setLoading(true);
    setError(null);
    try {
      const session = await api.login(email, "acme", role);
      setSession(session.accessToken, role);
      router.replace("/");
    } catch {
      setError(showcaseEnabled ? "Could not enter the showcase." : "Could not reach the incident service. Is the stack running?");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex min-h-[100dvh] items-center justify-center px-4 py-8 sm:px-6">
      <div className="w-full max-w-md">
        <div className="mb-8">
          <h1 className="font-display text-3xl tracking-tightest">SENTINEL</h1>
          <p className="mt-1 text-sm text-muted">
            {showcaseEnabled
              ? "Pick a role to explore a cost-free interactive portfolio dataset. No account or backend is required."
              : "Pick a role to explore the platform. Tokens are issued by the development auth endpoint and are not a production sign-in."}
          </p>
        </div>

        <div className="panel p-4 sm:p-5">
          <label className="label mb-1.5 block" htmlFor="email">
            Email
          </label>
          <input
            id="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className="mb-5 w-full rounded-sm border border-ink-600 bg-ink-900 px-3 py-2 font-mono text-sm text-paper outline-none focus:border-trace"
          />

          <p className="label mb-2">Role</p>
          <div className="mb-5 space-y-1.5">
            {ROLES.map((option) => (
              <button
                key={option.role}
                onClick={() => setRole(option.role)}
                className={`flex min-h-14 w-full flex-col items-start justify-center rounded-sm border px-3 py-2 text-left transition-colors ${
                  role === option.role
                    ? "border-trace/50 bg-trace/10"
                    : "border-ink-700 hover:border-ink-600"
                }`}
              >
                <span className="font-mono text-[11px] uppercase tracking-[0.12em] text-paper">{option.role}</span>
                <span className="text-xs text-muted">{option.description}</span>
              </button>
            ))}
          </div>

          {error && <p className="mb-3 text-sm text-critical">{error}</p>}

          <Button variant="primary" loading={loading} onClick={signIn} className="w-full justify-center">
            Enter
          </Button>
        </div>
      </div>
    </div>
  );
}
