import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "release-metadata.sh"


def find_bash():
    if os.name == "nt":
        for c in (r"C:/Program Files/Git/bin/bash.exe", r"C:/Program Files (x86)/Git/bin/bash.exe"):
            if Path(c).exists():
                return c
    return shutil.which("bash")


def to_posix(p):
    s = str(p).replace("\\", "/")
    if len(s) > 1 and s[1] == ":":
        s = "/" + s[0].lower() + s[2:]
    return s


@unittest.skipUnless(shutil.which("git"), "git not available")
class ReleaseMetadataIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.bash = find_bash()
        if not self.bash:
            self.skipTest("bash not available")
        if not SCRIPT.exists():
            self.skipTest("release-metadata.sh not found")
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        self._git("init", "-b", "main")
        self._git("config", "user.email", "t@example.com")
        self._git("config", "user.name", "T")
        self._git("config", "commit.gpgsign", "false")
        self._git("config", "tag.gpgsign", "false")
        self.vf = self.repo / "app" / "build.gradle.kts"
        self.vf.parent.mkdir(parents=True, exist_ok=True)

    def tearDown(self):
        self.tmp.cleanup()

    def _git(self, *args):
        return subprocess.run(["git", *args], cwd=str(self.repo), capture_output=True, text=True, check=True)

    def _write_version(self, code, name):
        self.vf.write_text(
            'android {\n    defaultConfig {\n        versionCode = %d\n        versionName = "%s"\n    }\n}\n' % (code, name),
            encoding="utf-8",
        )

    def _commit(self, msg):
        self._git("add", "-A")
        self._git("commit", "-m", msg)

    def _run(self):
        script = to_posix(SCRIPT)
        return subprocess.run(
            [self.bash, script],
            cwd=str(self.repo),
            capture_output=True,
            text=True,
        )

    def test_bump_detected_and_release_commit_is_head(self):
        self._write_version(1099, "0.9.4-plus.49")
        self._commit("code1099")
        self._write_version(1100, "0.9.4-plus.50")
        self._commit("code1100")
        (self.repo / "src.txt").write_text("x\n", encoding="utf-8")
        self._commit("unrelated compile fix")
        r = self._run()
        self.assertEqual(r.returncode, 0, r.stderr)
        head = self._git("rev-parse", "HEAD").stdout.strip()
        self.assertIn("release_commit=%s" % head, r.stdout)
        bump = self._git("rev-parse", "HEAD~1").stdout.strip()
        self.assertIn("current_bump=%s" % bump, r.stdout)
        self.assertNotEqual(bump, head)

    def test_same_code_new_version_fails(self):
        self._write_version(1099, "0.9.4-plus.49")
        self._commit("code1099")
        self._write_version(1099, "0.9.4-plus.50")
        self._commit("code1100")
        r = self._run()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("versionCode must increase", r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
