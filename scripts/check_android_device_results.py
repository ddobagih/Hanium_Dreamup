#!/usr/bin/env python3
"""Validate approved connected-test identities, not XML formatting or a fixed count."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify_results(root: Path, suite_name: str, expected: list[str]) -> int:
    if not expected or len(expected) != len(set(expected)):
        raise ValueError("approved test names must be non-empty and unique")
    results = sorted(root.rglob("TEST-*.xml"))
    if not results:
        raise ValueError("connected-test XML results are missing")
    actual: Counter[str] = Counter()
    for path in results:
        document = ET.parse(path).getroot()
        if document.tag not in {"testsuite", "testsuites"}:
            raise ValueError(f"unexpected XML root: {path}")
        for suite in document.iter():
            if suite.tag not in {"testsuite", "testsuites"}:
                continue
            for attribute in ("failures", "errors", "skipped", "disabled"):
                if int(suite.get(attribute, "0")) != 0:
                    raise ValueError(f"non-passing suite ({attribute}): {path}")
            cases = suite.findall("testcase")
            if not cases:
                # An aggregate <testsuites> may count child suites; a <testsuite> must list its cases.
                if suite.tag == "testsuite" and int(suite.get("tests", "0")) != 0:
                    raise ValueError(f"suite testcase count mismatch: {path}")
                continue
            if suite.get("name") != suite_name:
                raise ValueError(f"unapproved suite: {suite.get('name')}")
            if "tests" in suite.attrib and int(suite.attrib["tests"]) != len(cases):
                raise ValueError(f"suite testcase count mismatch: {path}")
            for case in cases:
                name = case.get("name", "")
                if case.get("classname", suite_name) != suite_name:
                    raise ValueError(f"unapproved testcase class: {name}")
                if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                    raise ValueError(f"non-passing testcase: {name}")
                actual[name] += 1
    if actual != Counter(expected):
        raise ValueError(f"approved testcase mismatch: expected={sorted(expected)}, actual={dict(actual)}")
    return sum(actual.values())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--expected-test", action="append", required=True)
    args = parser.parse_args()
    try:
        count = verify_results(args.results, args.suite, args.expected_test)
    except (OSError, ValueError, ET.ParseError) as exc:
        print(f"Android device result check FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"Android device result check PASS: {count} approved tests")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
