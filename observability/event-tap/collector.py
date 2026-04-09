from __future__ import annotations

import json
import os
import threading
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

TRACE_DIR = Path(os.getenv("TRACE_DIR", "/traces"))
CALL_EVENTS_URL = os.getenv("CALL_EVENTS_URL", "http://orchestrator:8080/v1/call-events")
MEDIA_EVENTS_URL = os.getenv("MEDIA_EVENTS_URL", "http://media-bridge:8081/v1/media/events")
RECONNECT_DELAY_SECONDS = float(os.getenv("EVENT_TRACE_RECONNECT_DELAY_SECONDS", "1.0"))


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def append_json_line(path: Path, payload: dict[str, object], lock: threading.Lock) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with lock:
        with path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, sort_keys=True))
            handle.write("\n")
            handle.flush()


def parse_event_data(data_lines: list[str]) -> object:
    raw_data = "\n".join(data_lines)
    if not raw_data:
        return None
    try:
        return json.loads(raw_data)
    except json.JSONDecodeError:
        return raw_data


def stream_events(name: str, url: str, output_path: Path, lock: threading.Lock) -> None:
    while True:
        event_type: str | None = None
        data_lines: list[str] = []
        append_json_line(
            output_path,
            {"captured_at": now_iso(), "kind": "stream_connecting", "stream": name, "url": url},
            lock,
        )
        try:
            request = urllib.request.Request(
                url,
                headers={"Accept": "text/event-stream", "Cache-Control": "no-cache", "User-Agent": "mimir-event-trace/1.0"},
            )
            with urllib.request.urlopen(request, timeout=30) as response:
                append_json_line(
                    output_path,
                    {
                        "captured_at": now_iso(),
                        "kind": "stream_connected",
                        "status": getattr(response, "status", None),
                        "stream": name,
                    },
                    lock,
                )
                for raw_line in response:
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                    if not line:
                        if event_type is None and not data_lines:
                            continue
                        append_json_line(
                            output_path,
                            {
                                "captured_at": now_iso(),
                                "data": parse_event_data(data_lines),
                                "event_type": event_type,
                                "kind": "event",
                                "stream": name,
                            },
                            lock,
                        )
                        event_type = None
                        data_lines = []
                        continue
                    if line.startswith(":"):
                        continue
                    if line.startswith("event:"):
                        event_type = line.partition(":")[2].lstrip()
                        continue
                    if line.startswith("data:"):
                        data_lines.append(line.partition(":")[2].lstrip())
        except Exception as exc:
            append_json_line(
                output_path,
                {"captured_at": now_iso(), "error": str(exc), "kind": "stream_error", "stream": name},
                lock,
            )
            time.sleep(RECONNECT_DELAY_SECONDS)


def main() -> None:
    TRACE_DIR.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()
    threads = [
        threading.Thread(
            target=stream_events,
            args=("orchestrator-call-events", CALL_EVENTS_URL, TRACE_DIR / "orchestrator-call-events.ndjson", lock),
            daemon=True,
        ),
        threading.Thread(
            target=stream_events,
            args=("media-bridge-events", MEDIA_EVENTS_URL, TRACE_DIR / "media-bridge-events.ndjson", lock),
            daemon=True,
        ),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()


if __name__ == "__main__":
    main()
