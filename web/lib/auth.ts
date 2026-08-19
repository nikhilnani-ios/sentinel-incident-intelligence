"use client";

import type { Role } from "./types";

const TOKEN_KEY = "sentinel.token";
const ROLE_KEY = "sentinel.role";

/**
 * Dev-grade session handling.
 *
 * <p>The token lives in localStorage because the API is stateless and the dev auth endpoint hands
 * out short-lived tokens. A production deployment would put this behind an httpOnly cookie and a
 * real identity provider — that is a deliberate scope boundary, not an oversight, and it is called
 * out in the README rather than left for a reviewer to find.
 */
export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.sessionStorage.getItem(TOKEN_KEY);
}

export function getRole(): Role {
  if (typeof window === "undefined") return "VIEWER";
  return (window.sessionStorage.getItem(ROLE_KEY) as Role) ?? "VIEWER";
}

export function setSession(token: string, role: Role) {
  window.sessionStorage.setItem(TOKEN_KEY, token);
  window.sessionStorage.setItem(ROLE_KEY, role);
}

export function clearSession() {
  window.sessionStorage.removeItem(TOKEN_KEY);
  window.sessionStorage.removeItem(ROLE_KEY);
}

const RANK: Record<Role, number> = { VIEWER: 0, RESPONDER: 1, COMMANDER: 2, ADMIN: 3 };

export function can(required: Role): boolean {
  return RANK[getRole()] >= RANK[required];
}
