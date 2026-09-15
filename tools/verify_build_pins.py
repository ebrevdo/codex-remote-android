#!/usr/bin/env python3
"""Verify build entry points before running any repository Gradle code."""

import hashlib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WRAPPER_SHA256 = "2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046"
DISTRIBUTION_SHA256 = "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"


def verify():
    jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    if hashlib.sha256(jar.read_bytes()).hexdigest() != WRAPPER_SHA256:
        raise SystemExit("Gradle wrapper JAR checksum mismatch")
    properties = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
    if "distributionSha256Sum=" + DISTRIBUTION_SHA256 not in properties:
        raise SystemExit("Gradle distribution checksum is missing or changed")
    metadata = ET.parse(ROOT / "gradle/verification-metadata.xml")
    namespace = {"v": "https://schema.gradle.org/dependency-verification"}
    if not metadata.findall(".//v:component", namespace):
        raise SystemExit("Dependency verification bootstrap is incomplete. See SECURITY.md.")
    for name in ("app/gradle.lockfile", "buildscript-gradle.lockfile"):
        if not (ROOT / name).is_file():
            raise SystemExit("Dependency locks are missing: " + name)
    if metadata.findtext(".//v:verify-metadata", namespaces=namespace) != "true":
        raise SystemExit("Dependency metadata verification must stay enabled")
    if metadata.findall(".//v:trusted-artifacts/v:trust", namespace):
        raise SystemExit("Broad artifact verification exemptions are not allowed")
    for artifact in metadata.findall(".//v:artifact", namespace):
        checksums = artifact.findall("v:sha256", namespace)
        if not checksums or any(len(checksum.attrib.get("value", "")) != 64 for checksum in checksums):
            raise SystemExit("Missing SHA-256 pin: " + artifact.attrib["name"])
    print("Wrapper checksum, verification metadata, and lockfile are present.")


if __name__ == "__main__":
    verify()
