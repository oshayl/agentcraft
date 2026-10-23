#!/usr/bin/env python3
"""Run client logic regressions without launching Minecraft or touching Gradle caches."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).with_suffix("")
CLIENT = ROOT / "mod/src/client/java/dev/agentcraft/client"
java_home = os.environ.get("JAVA_HOME")
if not java_home and Path("/usr/local/opt/openjdk@25").exists():
    java_home = "/usr/local/opt/openjdk@25"
javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
if not javac or not java:
    raise SystemExit("Java 25 is required; set JAVA_HOME to its installation directory")

sources = sorted(FIXTURES.rglob("*.java")) + [
    CLIENT / "foreman/Protocol.java",
    CLIENT / "goals/GoalScreen.java",
    CLIENT / "library/FileNotes.java",
    CLIENT / "library/LibraryScreen.java",
]
with tempfile.TemporaryDirectory(prefix="agentcraft-client-tests-") as build:
    subprocess.run([javac, "--release", "25", "-d", build, *map(str, sources)], check=True)
    subprocess.run([java, "-ea", "-cp", build, "ClientRegressions", *sys.argv[1:]], check=True)
