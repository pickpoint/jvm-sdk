#!/usr/bin/env python3
"""Write Maven Central + in-memory PGP signing props for CI.

SIGNING_KEY may be:
  - ASCII-armored private key (multiline or \\n-escaped)
  - base64 of that armored key (preferred for GitHub Secrets)
"""

from __future__ import annotations

import base64
import os
import sys
from pathlib import Path


def decode_signing_key(raw: str) -> str:
    raw = raw.strip().strip('"').strip("'")
    if not raw:
        raise SystemExit("SIGNING_KEY secret is empty")

    if "BEGIN PGP PRIVATE KEY BLOCK" in raw:
        key = raw.replace("\\n", "\n") if "\n" not in raw and "\\n" in raw else raw
        return key

    # GitHub-friendly: single-line base64 of the armored key
    try:
        decoded = base64.b64decode(raw, validate=True).decode("utf-8")
    except Exception as exc:  # noqa: BLE001
        raise SystemExit(
            "SIGNING_KEY must be an armored PGP private key or base64 of one"
        ) from exc

    if "BEGIN PGP PRIVATE KEY BLOCK" not in decoded:
        raise SystemExit("Decoded SIGNING_KEY is not an armored PGP private key")
    return decoded


def escape_prop(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n")


def main() -> None:
    key = decode_signing_key(os.environ.get("SIGNING_KEY_RAW", ""))
    key_id = os.environ.get("SIGNING_KEY_ID", "").strip().strip('"').strip("'")
    password = os.environ.get("SIGNING_PASSWORD", "")
    user = os.environ.get("MAVEN_USER", "")
    token = os.environ.get("MAVEN_PASSWORD", "")

    if not key_id:
        raise SystemExit("SIGNING_KEY_ID secret is empty")
    if not password:
        raise SystemExit("SIGNING_PASSWORD secret is empty")
    if not user or not token:
        raise SystemExit("MAVEN_CENTRAL_USERNAME/PASSWORD secrets are empty")

    # Match the format that works locally in ~/.gradle/gradle.properties
    props = Path.home() / ".gradle" / "gradle.properties"
    props.parent.mkdir(parents=True, exist_ok=True)
    block = "\n".join(
        [
            f"mavenCentralUsername={escape_prop(user)}",
            f"mavenCentralPassword={escape_prop(token)}",
            f"signingInMemoryKeyId={escape_prop(key_id)}",
            f"signingInMemoryKeyPassword={escape_prop(password)}",
            f"signingInMemoryKey={escape_prop(key)}",
            "",
        ]
    )
    props.write_text(block)
    props.chmod(0o600)
    print(
        "wrote",
        props,
        "key_lines=",
        len(key.splitlines()),
        "key_id_len=",
        len(key_id),
    )


if __name__ == "__main__":
    main()
    sys.exit(0)
