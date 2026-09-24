#!/usr/bin/env python3
"""Drive Frontier's guarded destructive acceptance through two real Paper boots."""

from __future__ import annotations

import argparse
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

READY_MARKER = "Done ("
PREPARED_MARKER = "FRONTIER_ACCEPTANCE_PREPARED"
RECLAIM_MARKER = "FRONTIER_ACCEPTANCE_RECLAIM_OK"
FAIL_MARKER = "FRONTIER_ACCEPTANCE_FAILED"
TOKEN = "I_UNDERSTAND_DISPOSABLE_WORLD"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-dir", type=Path, required=True)
    parser.add_argument("--paper-jar", type=Path, required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--java", default="java")
    return parser.parse_args()


def configure(server: Path, paper: Path, plugin: Path) -> Path:
    server.mkdir(parents=True, exist_ok=True)
    plugins = server / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    shutil.copy2(paper, server / "paper.jar")
    target_plugin = plugins / "EnthusiaFrontier.jar"
    shutil.copy2(plugin, target_plugin)
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server / "server.properties").write_text(
        "\n".join(
            (
                "server-ip=127.0.0.1",
                "server-port=25565",
                "max-players=2",
                "motd=Enthusia Sentinel isolated smoke test",
                "online-mode=false",
                "enforce-secure-profile=false",
                "spawn-protection=0",
                "view-distance=2",
                "simulation-distance=2",
                "pause-when-empty-seconds=0",
                "enable-rcon=false",
                "enable-query=false",
                "allow-flight=true",
                "level-seed=138138138",
                "sync-chunk-writes=true",
                "network-compression-threshold=-1",
                "difficulty=peaceful",
            )
        )
        + "\n",
        encoding="utf-8",
    )
    return target_plugin


class PaperCycle:
    def __init__(self, java: str, server: Path, cycle: int) -> None:
        self.server = server
        self.cycle = cycle
        self.lines: queue.Queue[str] = queue.Queue()
        self.output: list[str] = []
        self.process = subprocess.Popen(
            [java, "-Xms1G", "-Xmx2G", "-Dfile.encoding=UTF-8", "-jar", "paper.jar", "--nogui"],
            cwd=server,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            env={**os.environ, "TERM": "dumb"},
        )
        if self.process.stdout is None or self.process.stdin is None:
            raise RuntimeError("Paper subprocess pipes were not created")
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            decorated = f"[paper-{self.cycle}] {line}"
            sys.stdout.write(decorated)
            sys.stdout.flush()
            self.output.append(line)
            self.lines.put(line)

    def wait_for(self, marker: str, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None and self.lines.empty():
                raise RuntimeError(
                    f"Paper cycle {self.cycle} exited with {self.process.returncode} before marker {marker!r}"
                )
            try:
                line = self.lines.get(timeout=min(1.0, max(0.01, deadline - time.monotonic())))
            except queue.Empty:
                continue
            if FAIL_MARKER in line:
                raise RuntimeError(f"Paper cycle {self.cycle} reported acceptance failure: {line.strip()}")
            if marker in line:
                return
        raise TimeoutError(f"Paper cycle {self.cycle} timed out waiting for {marker!r}")

    def command(self, value: str) -> None:
        if self.process.poll() is not None:
            raise RuntimeError(f"Paper cycle {self.cycle} exited before command {value!r}")
        assert self.process.stdin is not None
        self.process.stdin.write(value + "\n")
        self.process.stdin.flush()

    def stop(self) -> None:
        if self.process.poll() is None:
            self.command("stop")
        try:
            code = self.process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=15)
            raise RuntimeError(f"Paper cycle {self.cycle} did not stop within 60 seconds")
        self.reader.join(timeout=5)
        if code != 0:
            raise RuntimeError(f"Paper cycle {self.cycle} exited with code {code}")

    def abort(self) -> None:
        if self.process.poll() is not None:
            return
        try:
            self.command("stop")
            self.process.wait(timeout=15)
        except (RuntimeError, subprocess.TimeoutExpired):
            self.process.kill()
            self.process.wait(timeout=15)


def run(java: str, server: Path) -> None:
    first = PaperCycle(java, server, 1)
    try:
        first.wait_for(READY_MARKER, 180)
        first.command(f"frontier acceptance prepare {TOKEN}")
        first.wait_for(PREPARED_MARKER, 180)
        first.stop()
    except BaseException:
        first.abort()
        raise

    state = server / "plugins" / "EnthusiaFrontier" / "acceptance-state.properties"
    if not state.is_file():
        raise RuntimeError("acceptance prepare marker was logged but durable state file is missing")

    second = PaperCycle(java, server, 2)
    try:
        second.wait_for(READY_MARKER, 180)
        second.command(f"frontier acceptance verify {TOKEN}")
        second.wait_for(RECLAIM_MARKER, 180)
        second.stop()
    except BaseException:
        second.abort()
        raise

    if state.exists():
        raise RuntimeError("acceptance state file remained after successful verification")
    combined = "".join(first.output + second.output)
    if PREPARED_MARKER not in combined or RECLAIM_MARKER not in combined:
        raise RuntimeError("required Frontier acceptance evidence markers are missing")
    print("FRONTIER_REAL_PAPER_ACCEPTANCE_OK")


def main() -> int:
    args = parse_args()
    paper = args.paper_jar.resolve()
    plugin = args.plugin_jar.resolve()
    server = args.server_dir.resolve()
    if not paper.is_file() or not plugin.is_file():
        raise FileNotFoundError("Paper or Frontier JAR is missing")
    configure(server, paper, plugin)
    run(args.java, server)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
