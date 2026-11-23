#!/usr/bin/env python3
"""Check tracked and otherwise publishable files before a public release.

Ignored build products and local settings are excluded. A tracked file is always
checked, even if a later ignore rule also matches it.

Usage:  python tools/check_public_repo.py
Exit code 0 = clean, 1 = findings.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Used only by the non-Git fallback. Git candidates never bypass checks by directory.
SKIP_DIRS = {
    ".git", ".gradle", ".kotlin", "build", "__pycache__", ".tmp", "tmp",
    "gen", "node_modules", ".idea", ".vscode", "release", "runtime",
    "models", "msys64",
}
BINARY_SUFFIXES = {
    ".gguf", ".ggml", ".safetensors", ".onnx", ".pt", ".pth", ".bin",
    ".exe", ".dll", ".so", ".dylib", ".zip", ".7z", ".tar", ".gz",
}
TEXT_SUFFIXES = {
    ".kt", ".kts", ".py", ".js", ".ts", ".jsx", ".tsx", ".json", ".md",
    ".ps1", ".cpp", ".cc", ".cxx", ".c", ".h", ".hpp", ".java", ".proto",
    ".txt", ".yml", ".yaml", ".toml", ".properties", ".ini", ".xml",
    ".html", ".htm", ".rpy", ".csv", ".sql", ".cfg", ".conf", ".rb",
    ".php", ".lua", ".css", ".scss", ".vue", ".sh", ".bat",
}
SKIP_TEXT_SUFFIXES = {
    ".png", ".ico", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".jar",
    ".rxdata", ".rvdata", ".rvdata2", ".woff", ".woff2", ".ttf", ".otf",
    ".mp3", ".ogg", ".wav", ".mp4", ".webm", ".pdf",
}
SPECIAL_TEXT_NAMES = {".gitignore", ".gitattributes", ".env", "Dockerfile", "gradlew"}
SENSITIVE_FILENAMES = {"settings.json", "relay.json", "library.json", ".env"}
MAX_BYTES = 5 * 1024 * 1024

SECRET_PATTERNS = [
    ("OpenAI-style key", re.compile(r"sk-[A-Za-z0-9_\-]{20,}")),
    ("GitHub token", re.compile(r"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})")),
    ("GitLab token", re.compile(r"glpat-[A-Za-z0-9_\-]{20,}")),
    ("AWS access key", re.compile(r"(?:AKIA|ASIA)[A-Z0-9]{16}")),
    ("Bearer token", re.compile(r"Bearer\s+[A-Za-z0-9_\-.]{24,}")),
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("hard-coded api key", re.compile(
        r"api[_-]?key\s*[:=]\s*[\"'][^\"']{16,}[\"']", re.IGNORECASE)),
]
PERSONAL_PATH_PATTERNS = [
    ("machine-specific Windows path", re.compile(
        r"(?<![A-Za-z0-9_])(?:[A-Za-z]:[\\/](?:Users[\\/][^\\/\s\"']+|AICode[\\/][^\s\"']+)|[D-Z]:[\\/][^\s\"']+)",
        re.IGNORECASE)),
    ("Unix home path", re.compile(r"(?:/h(?:ome)/|/U(?:sers)/)[^/\s\"']+/[^\s\"']+")),
]


def candidate_files() -> list[Path]:
    """Return tracked files plus untracked files that Git would allow adding."""
    try:
        output = subprocess.check_output(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=ROOT,
            text=True,
            encoding="utf-8",
            errors="replace",
            stderr=subprocess.DEVNULL,
        )
        return sorted(ROOT / item for item in output.split("\0") if item)
    except (OSError, subprocess.CalledProcessError):
        return sorted(
            path for path in ROOT.rglob("*")
            if not any(part in SKIP_DIRS for part in path.relative_to(ROOT).parts)
        )


def should_scan_text(path: Path) -> bool:
    suffix = path.suffix.lower()
    if suffix in SKIP_TEXT_SUFFIXES or suffix in BINARY_SUFFIXES:
        return False
    return not suffix or suffix in TEXT_SUFFIXES or path.name in SPECIAL_TEXT_NAMES


def main() -> int:
    findings: list[str] = []
    for path in candidate_files():
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT)
        suffix = path.suffix.lower()
        size = path.stat().st_size

        if path.name in SENSITIVE_FILENAMES:
            findings.append(f"[runtime config] {relative}")
        if suffix in BINARY_SUFFIXES:
            findings.append(f"[binary] {relative} ({size} bytes)")
        if size > MAX_BYTES:
            findings.append(f"[large]  {relative} ({size / 1024 / 1024:.1f} MB)")
        if not should_scan_text(path):
            continue

        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as error:
            findings.append(f"[read]   {relative}: {error}")
            continue

        for label, pattern in SECRET_PATTERNS + PERSONAL_PATH_PATTERNS:
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                # Do not echo secrets or personal paths into terminals and CI logs.
                findings.append(f"[{label}] {relative}:{line} (match redacted)")

    if findings:
        print("Repo is not ready for public release:\n")
        for item in findings:
            print("  " + item)
        print(f"\n{len(findings)} finding(s). See docs/COMPLIANCE.md.")
        return 1

    print("Clean: publishable files contain no blocked binaries, secrets or personal paths.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
