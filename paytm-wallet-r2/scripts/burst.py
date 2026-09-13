#!/usr/bin/env python3
"""One-command live probe for the Paytm R2 wallet invariants.

No third-party Python packages required.

Examples:
  python3 scripts/burst.py all
  python3 scripts/burst.py wallets --base-url http://localhost:8080
  python3 scripts/burst.py idempotency
  python3 scripts/burst.py contention
"""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class HttpResult:
    status: int
    body: dict[str, Any]


def http_json(base_url: str, method: str, path: str, token: str | None = None,
              payload: dict[str, Any] | None = None, timeout: int = 60) -> HttpResult:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    request = Request(base_url.rstrip("/") + path, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return HttpResult(response.status, json.loads(raw) if raw else {})
    except HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            body = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            body = {"raw": raw}
        return HttpResult(error.code, body)
    except URLError as error:
        raise RuntimeError(f"Cannot reach {base_url}: {error}") from error


def fresh_token(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4()}"


def create_wallet(base_url: str, token: str) -> dict[str, Any]:
    result = http_json(base_url, "POST", "/wallets", token)
    require(result.status == 200, f"wallet create failed: HTTP {result.status}: {result.body}")
    return result.body


def get_wallet(base_url: str, token: str, wallet_id: str) -> dict[str, Any]:
    result = http_json(base_url, "GET", f"/wallets/{wallet_id}", token)
    require(result.status == 200, f"wallet get failed: HTTP {result.status}: {result.body}")
    return result.body


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def wallet_race(base_url: str, workers: int = 50) -> None:
    print(f"\n[RUN ] Gate 1: concurrent get-or-create ({workers} requests)")
    token = fresh_token("race-user")
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(http_json, base_url, "POST", "/wallets", token) for _ in range(workers)]
        results = [future.result() for future in as_completed(futures)]

    require(all(r.status == 200 for r in results),
            f"not all wallet requests returned 200: {[r.status for r in results]}")
    ids = {r.body.get("wallet_id") for r in results}
    require(None not in ids, f"missing wallet_id in response(s): {results[:3]}")
    require(len(ids) == 1, f"expected one wallet id, got {len(ids)}: {ids}")
    print(f"[PASS] Gate 1: {workers} requests returned exactly 1 wallet: {next(iter(ids))}")


def idempotency_storm(base_url: str, workers: int = 30) -> None:
    print(f"\n[RUN ] Gate 2: idempotent retry storm ({workers} concurrent requests)")
    source_token = fresh_token("source")
    destination_token = fresh_token("destination")
    source = create_wallet(base_url, source_token)
    destination = create_wallet(base_url, destination_token)
    amount = 1_000
    key = f"idem-{uuid.uuid4()}"

    source_before = get_wallet(base_url, source_token, source["wallet_id"])["balance_paise"]
    destination_before = get_wallet(base_url, destination_token, destination["wallet_id"])["balance_paise"]

    payload = {
        "from": source["wallet_id"],
        "to": destination["wallet_id"],
        "amount_paise": amount,
        "idempotency_key": key,
    }

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [
            pool.submit(http_json, base_url, "POST", "/transfers", source_token, payload)
            for _ in range(workers)
        ]
        results = [future.result() for future in as_completed(futures)]

    require(all(r.status == 200 for r in results),
            f"not all retries returned 200: {[r.status for r in results]}")
    canonical = json.dumps(results[0].body, sort_keys=True)
    require(all(json.dumps(r.body, sort_keys=True) == canonical for r in results),
            "idempotent retry responses were not identical")

    source_after = get_wallet(base_url, source_token, source["wallet_id"])["balance_paise"]
    destination_after = get_wallet(base_url, destination_token, destination["wallet_id"])["balance_paise"]
    transfer_ids = {r.body.get("id") for r in results}

    require(len(transfer_ids) == 1, f"expected one transfer id, got {transfer_ids}")
    require(source_after == source_before - amount,
            f"source was not debited exactly once: {source_before} -> {source_after}")
    require(destination_after == destination_before + amount,
            f"destination was not credited exactly once: {destination_before} -> {destination_after}")

    conflict_payload = dict(payload)
    conflict_payload["amount_paise"] = amount + 1
    conflict = http_json(base_url, "POST", "/transfers", source_token, conflict_payload)
    require(conflict.status == 409,
            f"same key/different body should be 409, got HTTP {conflict.status}: {conflict.body}")

    print(f"[PASS] Gate 2: one transfer {next(iter(transfer_ids))}; one debit/credit; all responses identical; conflict=409")


def contention(base_url: str, requests: int = 300, workers: int = 40) -> None:
    print(f"\n[RUN ] Gate 3: conservation + no-overdraft ({requests} transfers, concurrency={workers})")
    tokens = [fresh_token("A"), fresh_token("B"), fresh_token("C")]
    wallets = [create_wallet(base_url, token) for token in tokens]
    before_balances = [
        get_wallet(base_url, token, wallet["wallet_id"])["balance_paise"]
        for token, wallet in zip(tokens, wallets)
    ]
    before_total = sum(before_balances)

    jobs: list[tuple[str, dict[str, Any]]] = []
    for i in range(requests):
        from_index = i % 3
        # Alternates directions, guaranteeing A->B/B->A style contention.
        to_index = (from_index + (1 if i % 2 == 0 else 2)) % 3
        amount = 500_000 if i % 19 == 0 else 200 + (i % 7) * 100
        jobs.append((tokens[from_index], {
            "from": wallets[from_index]["wallet_id"],
            "to": wallets[to_index]["wallet_id"],
            "amount_paise": amount,
            "idempotency_key": f"contention-{uuid.uuid4()}",
        }))

    results: list[HttpResult] = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [
            pool.submit(http_json, base_url, "POST", "/transfers", token, payload)
            for token, payload in jobs
        ]
        for future in as_completed(futures):
            results.append(future.result())

    unexpected = [r for r in results if r.status != 200]
    require(not unexpected,
            f"contention produced non-200 responses: {[(r.status, r.body) for r in unexpected[:5]]}")

    after_balances = [
        get_wallet(base_url, token, wallet["wallet_id"])["balance_paise"]
        for token, wallet in zip(tokens, wallets)
    ]
    after_total = sum(after_balances)
    statuses = [r.body.get("status") for r in results]
    succeeded = statuses.count("SUCCEEDED")
    declined = statuses.count("DECLINED")

    require(after_total == before_total,
            f"conservation failed: before={before_total}, after={after_total}")
    require(all(balance >= 0 for balance in after_balances),
            f"negative balance observed: {after_balances}")
    require(declined > 0, "expected at least one insufficient-funds decline")

    print(f"[PASS] Gate 3: total {before_total} -> {after_total}; balances={after_balances}; "
          f"succeeded={succeeded}; declined={declined}; no negatives")


def health(base_url: str) -> None:
    result = http_json(base_url, "GET", "/health")
    require(result.status == 200, f"health endpoint failed: HTTP {result.status}: {result.body}")
    print(f"[PASS] Health: {result.body.get('status', result.body)}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe wallet concurrency invariants")
    parser.add_argument("mode", choices=["all", "wallets", "idempotency", "contention"], nargs="?", default="all")
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()

    try:
        health(args.base_url)
        if args.mode in ("all", "wallets"):
            wallet_race(args.base_url)
        if args.mode in ("all", "idempotency"):
            idempotency_storm(args.base_url)
        if args.mode in ("all", "contention"):
            contention(args.base_url)
        print("\nALL REQUESTED PROBES PASSED")
        return 0
    except (AssertionError, RuntimeError) as error:
        print(f"\n[FAIL] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
