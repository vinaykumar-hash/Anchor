"""
ContextMemory Sync Server — runs alongside the AI processor.
Provides REST endpoints for cross-device memory sync over local WiFi.
Uses Python's built-in http.server (zero extra dependencies).
"""

import json
import os
import socket
import threading
import time
from datetime import datetime
from http.server import HTTPServer, ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs

# ── paths ────────────────────────────────────────────────────────────
APP_DATA = Path(os.getenv("LOCALAPPDATA", "")) / "com.contextmemory.app"
DB_PATH = APP_DATA / "lancedb"
CAPTURES_DIR = APP_DATA / "captures"

SYNC_PORT = 8473

# Reference to the AIManager (set by ai_processor.py at startup)
_ai_manager = None
_last_sync_time = 0
_cached_ip = None


def set_ai_manager(mgr):
    """Called by ai_processor.py to share the AIManager instance."""
    global _ai_manager
    _ai_manager = mgr


def get_local_ip():
    """Get the local IP address, prioritizing actual WiFi/Ethernet adapters."""
    global _cached_ip
    if _cached_ip:
        return _cached_ip
        
    try:
        # Method 1: Standard connection trick
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        
        # Filter out common virtual IPs (Docker, WSL, VMware)
        if ip.startswith("172.") or ip.startswith("10.") or ip.startswith("127."):
            # Try Method 2: Iterate interfaces
            hostname = socket.gethostname()
            addresses = socket.gethostbyname_ex(hostname)[2]
            for addr in addresses:
                if addr.startswith("192.168."):
                    ip = addr
                    break
        _cached_ip = ip
        return ip
    except Exception:
        try:
            _cached_ip = socket.gethostbyname(socket.gethostname())
            return _cached_ip
        except Exception:
            return "127.0.0.1"


class SyncHandler(BaseHTTPRequestHandler):
    """HTTP handler for sync endpoints."""

    def log_message(self, format, *args):
        """Suppress default logging to stderr (Rust inherits stderr)."""
        pass

    def _send_json(self, data, status=200):
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))
        except Exception:
            # Avoid crashing the server if the client disconnected prematurely
            pass

    def _send_error(self, status, message):
        self._send_json({"error": message}, status)

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        params = parse_qs(parsed.query)

        if path == "/sync/status":
            self._handle_status()
        elif path == "/sync/manifest":
            self._handle_manifest()
        elif path == "/sync/entries":
            after = params.get("after", [None])[0]
            self._handle_get_entries(after)
        elif path.startswith("/sync/screenshot/"):
            filename = path.split("/sync/screenshot/", 1)[-1]
            self._handle_screenshot(filename)
        else:
            self._send_error(404, "Not found")

    def do_POST(self):
        if self.path == "/sync/entries":
            self._handle_post_entries()
        else:
            self._send_error(404, "Not found")

    def do_OPTIONS(self):
        """Handle CORS preflight."""
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def _handle_status(self):
        mgr = _ai_manager
        count = 0
        if mgr:
            try:
                table = mgr._db()
                count = table.count_rows()
            except Exception:
                pass
        self._send_json({
            "deviceName": socket.gethostname(),
            "deviceType": "pc",
            "entryCount": count,
            "lastSyncTime": _last_sync_time,
            "ip": get_local_ip(),
            "port": SYNC_PORT,
        })

    def _handle_manifest(self):
        mgr = _ai_manager
        if not mgr:
            self._send_json({"entries": []})
            return
        try:
            table = mgr._db()
            rows = table.to_pandas()
            manifest = []
            for _, row in rows.iterrows():
                manifest.append({
                    "timestamp": row.get("timestamp", ""),
                    "textHash": hash(str(row.get("text", ""))[:80]),
                })
            self._send_json({"entries": manifest})
        except Exception as e:
            self._send_error(500, str(e))

    def _handle_get_entries(self, after=None):
        mgr = _ai_manager
        if not mgr:
            self._send_json({"entries": []})
            return
        try:
            table = mgr._db()
            rows = table.to_pandas()
            entries = []
            for _, row in rows.iterrows():
                text = str(row.get("text", ""))
                image_path = str(row.get("image_path", ""))
                timestamp_str = str(row.get("timestamp", ""))

                # Try to parse timestamp for filtering
                try:
                    ts_epoch = int(
                        datetime.fromisoformat(timestamp_str).timestamp() * 1000
                    )
                except Exception:
                    ts_epoch = 0

                if after and ts_epoch <= int(after):
                    continue

                entries.append({
                    "text": text,
                    "packageName": "pc",
                    "timestamp": ts_epoch,
                    "screenshotFilename": Path(image_path).name if image_path else "",
                    "sourceDevice": "pc",
                })
            self._send_json({"entries": entries})
        except Exception as e:
            self._send_error(500, str(e))

    def _handle_post_entries(self):
        global _last_sync_time
        mgr = _ai_manager
        if not mgr:
            self._send_error(503, "AI manager not ready")
            return
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length).decode("utf-8")
            data = json.loads(body)

            table = mgr._db()
            embedder = mgr._embedder()

            # Get existing timestamps for dedup
            existing = set()
            try:
                rows = table.to_pandas()
                for _, row in rows.iterrows():
                    existing.add(str(row.get("text", ""))[:80].lower())
            except Exception:
                pass

            imported = 0
            skipped = 0
            for entry in data.get("entries", []):
                text = entry.get("text", "")
                text_key = text[:80].lower()

                # Dedup
                if text_key in existing:
                    skipped += 1
                    continue

                # Re-embed using local model
                vec = embedder.encode(text).tolist()
                table.add([{
                    "vector": vec,
                    "text": text,
                    "image_path": "",
                    "timestamp": datetime.now().isoformat(),
                }])
                existing.add(text_key)
                imported += 1

            _last_sync_time = int(datetime.now().timestamp() * 1000)
            self._send_json({
                "status": "success",
                "imported": imported,
                "skipped": skipped,
            })
        except Exception as e:
            self._send_error(500, str(e))

    def _handle_screenshot(self, filename):
        filepath = CAPTURES_DIR / filename
        if filepath.exists():
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.end_headers()
            with open(filepath, "rb") as f:
                self.wfile.write(f.read())
        else:
            self._send_error(404, "Screenshot not found")


def start_sync_server():
    """Start the sync HTTP server and UDP heartbeat discovery."""
    try:
        # 1. Start Multi-threaded HTTP Server
        server = ThreadingHTTPServer(("0.0.0.0", SYNC_PORT), SyncHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        
        ip = get_local_ip()
        import sys
        print(f"[SyncServer] HTTP started on {ip}:{SYNC_PORT}", file=sys.stderr)

        # 2. Start UDP Heartbeat (for auto-discovery)
        def run_discovery():
            UDP_PORT = 8474
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.bind(("", UDP_PORT))
            
            # Message we send
            my_msg = json.dumps({
                "type": "context_memory_discovery",
                "name": socket.gethostname(),
                "ip": ip,
                "port": SYNC_PORT
            }).encode()
            
            last_broadcast = 0
            while True:
                now = time.time()
                # Broadcast our presence
                if now - last_broadcast > 5:
                    try:
                        sock.sendto(my_msg, ("255.255.255.255", UDP_PORT))
                        last_broadcast = now
                    except Exception: pass
                
                # Listen for other devices (phones)
                try:
                    sock.settimeout(1.0)
                    data, addr = sock.recvfrom(1024)
                    try:
                        info = json.loads(data.decode())
                        if info.get("type") == "context_memory_discovery" and info.get("ip") != ip:
                            # Update paired IP if this is a known device name but new IP
                            # Or just auto-pair if we have none
                            from ai_processor import save_paired_ip, get_paired_ip
                            if get_paired_ip() is None:
                                print(f"[Sync] Auto-pairing with {info.get('name')} at {info.get('ip')}", file=sys.stderr)
                                save_paired_ip(info.get("ip"))
                    except Exception: pass
                except socket.timeout:
                    pass
                except Exception: pass
                
        hb_thread = threading.Thread(target=run_discovery, daemon=True)
        hb_thread.start()
        
        # 3. Self-Diagnostic Check
        def run_self_test():
            time.sleep(3) # Wait for server to settle
            try:
                import urllib.request
                with urllib.request.urlopen(f"http://127.0.0.1:{SYNC_PORT}/sync/status", timeout=5) as resp:
                    if resp.status == 200:
                        print(f"[SyncServer] ✅ Self-test passed! Server is reachable locally.", file=sys.stderr)
            except Exception as e:
                print(f"[SyncServer] ❌ Self-test FAILED: {e}", file=sys.stderr)
                print(f"[SyncServer] HINT: Your PC might be blocking its own port (8473). Check for VPNs or Proxies.", file=sys.stderr)
        
        threading.Thread(target=run_self_test, daemon=True).start()
        
        return server
    except Exception as e:
        import sys
        print(f"[SyncServer] Failed to start: {e}", file=sys.stderr)
        return None
