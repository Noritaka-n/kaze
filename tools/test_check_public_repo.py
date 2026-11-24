#!/usr/bin/env python3
"""End-to-end tests for tools/check_public_repo.py (standard library only)."""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().with_name("check_public_repo.py")


class PublicRepoCheckTest(unittest.TestCase):
    def run_checker(self, files: dict[str, str | bytes]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="kaze-public-check-") as temporary:
            root = Path(temporary)
            (root / "tools").mkdir()
            shutil.copy2(SCRIPT, root / "tools" / SCRIPT.name)
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                if isinstance(content, bytes):
                    path.write_bytes(content)
                else:
                    path.write_text(content, encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", "-f", "."], cwd=root, check=True)
            return subprocess.run(
                [sys.executable, str(root / "tools" / SCRIPT.name)],
                cwd=root,
                text=True,
                capture_output=True,
                check=False,
            )

    def test_blocks_keys_binaries_unknown_extensions_and_utf16(self) -> None:
        token = "sk-" + "a" * 32
        bearer = "Bearer " + "b" * 32
        private_path = "D:" + "\\private\\fixture"
        private_key = "-----BEGIN " + "PRIVATE KEY-----\nfixture"
        result = self.run_checker({
            ".env.example": f'API_KEY="{token}"\n',
            "private.pem": private_key,
            "library.jar": b"PK\x03\x04fixture",
            "installer.msi": b"fixture",
            "notes.custom": bearer + "\n",
            "secret.ps1": (bearer + "\n" + private_path + "\n").encode("utf-16"),
        })
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        for expected in (
            "[OpenAI-style key] .env.example:1",
            "[private key file] private.pem",
            "[private key] private.pem:1",
            "[binary] library.jar",
            "[binary] installer.msi",
            "[Bearer token] notes.custom:1",
            "[Bearer token] secret.ps1:1",
            "[machine-specific Windows path] secret.ps1:2",
        ):
            self.assertIn(expected, result.stdout)
        self.assertNotIn(token, result.stdout)
        self.assertNotIn(bearer, result.stdout)
        self.assertNotIn(private_path, result.stdout)

    def test_blocks_tracked_env_and_key_filenames(self) -> None:
        result = self.run_checker({
            ".env.production": "PLACEHOLDER=value\n",
            "signing.key": "placeholder\n",
        })
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("[runtime config] .env.production", result.stdout)
        self.assertIn("[private key file] signing.key", result.stdout)

    def test_allows_placeholder_env_example_and_images(self) -> None:
        result = self.run_checker({
            "README.md": "Fixture documentation\n",
            ".env.example": 'API_KEY="your-key"\n',
            "assets/logo.png": b"\x89PNG\r\n\x1a\nfixture",
            "src/shell/gradle/wrapper/gradle-wrapper.jar": b"PK\x03\x04wrapper",
        })
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Clean:", result.stdout)


if __name__ == "__main__":
    unittest.main()
