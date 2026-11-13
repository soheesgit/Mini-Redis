#!/usr/bin/env python3
"""Measure the convenience-store product API over a persistent HTTP connection."""

import argparse
import http.client
import statistics
import time

MODES = ("BYPASS", "MISS", "HIT")
PRODUCT_PATH = "/api/store/products/cola"
CACHE_PATH = "/api/cache/store:product:cola"


def measure(connection, mode, expected_body):
    if mode == "MISS":
        # Invalidation sets up the miss and is outside the measured interval.
        connection.request("DELETE", CACHE_PATH)
        invalidation = connection.getresponse()
        invalidation.read()
        if invalidation.status not in (204, 404):
            raise RuntimeError(f"Expected DELETE 204 or 404, got {invalidation.status}")

    path = PRODUCT_PATH + "?cached=false" if mode == "BYPASS" else PRODUCT_PATH
    start = time.perf_counter_ns()
    connection.request("GET", path)
    response = connection.getresponse()
    body = response.read()
    elapsed_ms = (time.perf_counter_ns() - start) / 1_000_000

    if response.status != 200 or response.getheader("X-Cache") != mode or body != expected_body:
        raise RuntimeError(f"Unexpected {mode} response: HTTP {response.status}, X-Cache={response.getheader('X-Cache')}")
    return elapsed_ms


def run_round(connection, iterations, expected_body):
    samples = {mode: [] for mode in MODES}
    for index in range(iterations):
        # Rotate the order so each mode runs in each position.
        offset = index % len(MODES)
        for mode in MODES[offset:] + MODES[:offset]:
            samples[mode].append(measure(connection, mode, expected_body))
    return samples


def print_table(rounds):
    print("| Round | Mode | Requests | Mean (ms) | Median (ms) |")
    print("| --- | --- | ---: | ---: | ---: |")
    for label, samples in rounds:
        for mode in MODES:
            times = samples[mode]
            print(f"| {label} | {mode} | {len(times)} | {statistics.mean(times):.4f} | {statistics.median(times):.4f} |")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--warmup", type=int, default=150, help="warm-up requests per mode")
    parser.add_argument("--requests", type=int, default=300, help="measured requests per mode per round")
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args()
    if min(args.warmup, args.requests, args.rounds) < 1:
        parser.error("--warmup, --requests and --rounds must be positive")

    connection = http.client.HTTPConnection("127.0.0.1", args.port, timeout=5)
    try:
        connection.request("GET", PRODUCT_PATH + "?cached=false")
        response = connection.getresponse()
        expected_body = response.read()
        if response.status != 200 or response.getheader("X-Cache") != "BYPASS":
            raise RuntimeError("The origin request failed")

        # Ensure HIT is available before the first measured sequence.
        connection.request("GET", PRODUCT_PATH)
        response = connection.getresponse()
        if response.read() != expected_body or response.status != 200:
            raise RuntimeError("Failed to populate the cache with the origin response")

        run_round(connection, args.warmup, expected_body)
        rounds = [
            (number, run_round(connection, args.requests, expected_body))
            for number in range(1, args.rounds + 1)
        ]
        total = {mode: [value for _, samples in rounds for value in samples[mode]] for mode in MODES}
        print_table(rounds + [("All", total)])
    finally:
        connection.close()


if __name__ == "__main__":
    main()
