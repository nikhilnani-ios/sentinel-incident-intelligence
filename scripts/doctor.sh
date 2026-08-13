#!/usr/bin/env bash
set -euo pipefail

failures=0

check() {
  local label="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    printf 'ok   %s\n' "$label"
  else
    printf 'fail %s\n' "$label"
    failures=$((failures + 1))
  fi
}

check "Docker CLI" docker --version
check "Docker Compose v2" docker compose version
check "Docker daemon" docker info
check "Python 3 (demo seed)" python3 --version

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  check "Compose configuration" docker compose config --quiet
fi

if ((failures > 0)); then
  printf '\n%d prerequisite check(s) failed. Start Docker Desktop and rerun make doctor.\n' "$failures"
  exit 1
fi

printf '\nLocal prerequisites are ready. Run: make up\n'
