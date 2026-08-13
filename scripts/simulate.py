#!/usr/bin/env python3
"""
Fires realistic traffic at a running Sentinel stack.

Two scenarios:

  cascade  A payment-gateway deploy degrades checkout, which cascades upstream through the
           edge gateway. Produces one correlated incident with a linked deployment, which is
           the thing worth demoing: four services alerting, one incident.

  storm    High-volume flapping alerts plus noisy logs. Exercises fingerprint deduplication and
           the token-bucket rate limiter, and should produce far fewer incidents than signals.

Standard library only, so it runs anywhere python3 does.
"""

from __future__ import annotations
import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

DEFAULT_URL = "http://localhost:8081"
DEFAULT_AUTH_URL = "http://localhost:8083"
TENANT = "acme"


def iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def post(base_url: str, path: str, body: dict, token: str | None) -> dict | None:
    request = urllib.request.Request(
        f"{base_url}{path}",
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        print(f"  ! {path} -> {error.code} {error.read()[:200].decode(errors='replace')}", file=sys.stderr)
    except urllib.error.URLError as error:
        print(f"  ! cannot reach {base_url}: {error.reason}", file=sys.stderr)
        sys.exit(1)
    return None


def issue_local_token(auth_url: str) -> str:
    """Obtain a demo token from incident-service's local-profile auth endpoint."""
    request = urllib.request.Request(
        f"{auth_url}/v1/auth/token",
        data=json.dumps({
            "email": "simulator@acme.io",
            "tenantId": TENANT,
            "role": "COMMANDER",
        }).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.loads(response.read())["accessToken"]
    except (urllib.error.HTTPError, urllib.error.URLError, KeyError, json.JSONDecodeError) as error:
        print(f"  ! could not obtain a local token from {auth_url}: {error}", file=sys.stderr)
        print("    Is incident-service healthy and running with SPRING_PROFILES_ACTIVE=local?", file=sys.stderr)
        sys.exit(1)


def alert(service: str, name: str, severity: str, description: str, when: datetime, **labels) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "serviceKey": service,
        "occurredAt": iso(when),
        "labels": {"env": "production", "region": "us-east-2", **labels},
        "alertName": name,
        "severity": severity,
        "description": description,
        "source": "prometheus",
        "resolved": False,
        "runbookUrl": f"https://runbooks.acme.io/{service}/{name.lower()}",
    }


def metric(service: str, name: str, value: float, threshold: float, when: datetime, unit="ratio") -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "serviceKey": service,
        "occurredAt": iso(when),
        "labels": {"env": "production"},
        "metricName": name,
        "value": value,
        "unit": unit,
        "threshold": threshold,
        "comparison": "ABOVE",
    }


def log(service: str, message: str, when: datetime, occurrences=1, level="ERROR") -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "serviceKey": service,
        "occurredAt": iso(when),
        "labels": {"env": "production", "pod": f"{service}-{uuid.uuid4().hex[:8]}"},
        "level": level,
        "message": message,
        "loggerName": f"io.acme.{service.replace('-', '.')}",
        "traceId": uuid.uuid4().hex,
        "occurrences": occurrences,
    }


def cascade(base_url: str, token: str | None) -> None:
    """A bad deploy to payment-gateway takes down checkout, then the edge."""
    now = datetime.now(timezone.utc)
    deploy_at = now - timedelta(minutes=6)

    print("→ payment-gateway v2.4.1 ships")
    post(base_url, "/v1/ingest/deployments/single", {
        "eventId": str(uuid.uuid4()),
        "serviceKey": "payment-gateway",
        "occurredAt": iso(deploy_at),
        "labels": {"env": "production"},
        "version": "v2.4.1",
        "commitSha": uuid.uuid4().hex[:40],
        "deployedBy": "ci-bot",
        "environment": "production",
        "changelogUrl": "https://github.com/acme/payment-gateway/compare/v2.4.0...v2.4.1",
        "status": "SUCCEEDED",
    }, token)
    time.sleep(1.5)

    print("→ payment-gateway starts failing")
    post(base_url, "/v1/ingest/alerts", {"items": [
        alert("payment-gateway", "HighErrorRate", "CRITICAL",
              "5xx rate above 8% for 3 minutes", now - timedelta(minutes=4), alertname="HighErrorRate"),
    ]}, token)
    post(base_url, "/v1/ingest/metrics", {"items": [
        metric("payment-gateway", "http_server_error_ratio", 0.11, 0.02, now - timedelta(minutes=4)),
        metric("payment-gateway", "http_request_duration_p99", 4.8, 1.0,
               now - timedelta(minutes=4), unit="seconds"),
    ]}, token)
    post(base_url, "/v1/ingest/logs", {"items": [
        log("payment-gateway",
            "ConnectionPoolTimeout: could not acquire connection from pool after 5000ms",
            now - timedelta(minutes=4), occurrences=48),
    ]}, token)
    time.sleep(2)

    print("→ checkout-api degrades behind it")
    post(base_url, "/v1/ingest/alerts", {"items": [
        alert("checkout-api", "CheckoutFailureRate", "CRITICAL",
              "Checkout completion rate dropped to 61%", now - timedelta(minutes=3)),
        alert("checkout-api", "UpstreamLatency", "HIGH",
              "p99 latency to payment-gateway above 4s", now - timedelta(minutes=3)),
    ]}, token)
    post(base_url, "/v1/ingest/logs", {"items": [
        log("checkout-api", "PaymentGatewayException: upstream returned 503 for order 88213",
            now - timedelta(minutes=3), occurrences=132),
    ]}, token)
    time.sleep(2)

    print("→ the edge notices")
    post(base_url, "/v1/ingest/alerts", {"items": [
        alert("edge-gateway", "ElevatedErrorRate", "HIGH",
              "Public 5xx rate above 3%", now - timedelta(minutes=2)),
    ]}, token)
    post(base_url, "/v1/ingest/metrics", {"items": [
        metric("edge-gateway", "http_server_error_ratio", 0.037, 0.03, now - timedelta(minutes=2)),
    ]}, token)

    print("\nDone. Open http://localhost:3000 — expect one incident spanning three services,")
    print("with the payment-gateway deployment linked as the top suspect.")


def storm(base_url: str, token: str | None, seconds: int = 30) -> None:
    """Flapping alerts and log spam: many signals, few incidents."""
    services = ["search-api", "inventory-api", "pricing-engine", "notification-api"]
    started = time.time()
    sent = 0

    print(f"→ generating a signal storm for {seconds}s")
    while time.time() - started < seconds:
        now = datetime.now(timezone.utc)
        batch = []

        for service in services:
            # The same alert repeatedly: identical fingerprint, so it should deduplicate rather
            # than open a new incident each time.
            batch.append(alert(service, "PodRestartLoop", random.choice(["MEDIUM", "HIGH"]),
                               "Pod restarted 4 times in 10 minutes", now))

        post(base_url, "/v1/ingest/alerts", {"items": batch}, token)
        post(base_url, "/v1/ingest/logs", {"items": [
            # Volatile identifiers vary every time; normalisation should still collapse these.
            log(random.choice(services),
                f"Timeout calling downstream service after {random.randint(1000, 9000)}ms "
                f"(request {uuid.uuid4()})",
                now, occurrences=random.randint(1, 30))
            for _ in range(6)
        ]}, token)

        sent += len(batch) + 6
        time.sleep(0.4)

    print(f"\nSent {sent} signals. Check the incident list: the count should be a small fraction of that,")
    print("and the ingest dashboard should show a high duplicate rate.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scenario", choices=["cascade", "storm"])
    parser.add_argument("--url", default=DEFAULT_URL, help=f"ingest service base URL (default {DEFAULT_URL})")
    parser.add_argument("--auth-url", default=DEFAULT_AUTH_URL,
                        help=f"incident service base URL used for local auth (default {DEFAULT_AUTH_URL})")
    parser.add_argument("--token", default=None,
                        help="bearer token; when omitted, one is obtained from the local auth endpoint")
    parser.add_argument("--seconds", type=int, default=30, help="storm duration")
    args = parser.parse_args()

    token = args.token or issue_local_token(args.auth_url)

    if args.scenario == "cascade":
        cascade(args.url, token)
    else:
        storm(args.url, token, args.seconds)


if __name__ == "__main__":
    main()
