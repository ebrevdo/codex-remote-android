#!/usr/bin/env python3
"""Query OSV for exact Maven versions in reviewed Gradle verification metadata."""

import json
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    tree = ET.parse(root / "gradle/verification-metadata.xml")
    namespace = {"v": "https://schema.gradle.org/dependency-verification"}
    packages = sorted({
        (component.attrib["group"] + ":" + component.attrib["name"], component.attrib["version"])
        for component in tree.findall(".//v:component", namespace)
    })
    if not packages:
        raise SystemExit("No resolved dependency inventory; complete the reviewed bootstrap first.")

    findings = []
    for start in range(0, len(packages), 100):
        batch = packages[start:start + 100]
        payload = {
            "queries": [
                {"package": {"ecosystem": "Maven", "name": name}, "version": version}
                for name, version in batch
            ]
        }
        request = urllib.request.Request(
            "https://api.osv.dev/v1/querybatch",
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            results = json.load(response)["results"]
        if len(results) != len(batch) or any(
            "error" in result or result.get("next_page_token") for result in results
        ):
            raise SystemExit("Incomplete advisory response; review manually.")

        for (name, version), result in zip(batch, results):
            if result.get("vulns"):
                findings.append({
                    "package": name,
                    "version": version,
                    "advisories": [advisory["id"] for advisory in result["vulns"]],
                })

    print(json.dumps({"checked": len(packages), "findings": findings}, indent=2))
    raise SystemExit(1 if findings else 0)


if __name__ == "__main__":
    main()
