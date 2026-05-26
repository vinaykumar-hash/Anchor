"""
ContextMemory AI Processor — Python sidecar for the Tauri app.
Communicates via JSON-RPC over stdin/stdout.

CRITICAL:  llama-cpp-python (and its CUDA/CLIP backends) print diagnostic
messages directly to C-level stdout / stderr.  If any of those bytes leak
into the pipe that Rust reads, the JSON parse on the other side explodes.

Strategy
--------
1.  *Before* importing anything heavy we ``os.dup`` the real stdout fd.
2.  We then redirect C-level fd-1 (stdout) → fd-2 (stderr) so every
    subsequent C printf lands on stderr, which Rust already inherits.
3.  All of our own JSON output goes through ``_send()`` which writes
    directly to the saved fd with ``os.write``.
4.  We read stdin through the *original* ``sys.stdin.buffer`` (fd-0),
    which is untouched.
"""

import sys
import os

# ── step 1: capture the REAL stdout fd before anything else ──────────
_REAL_STDOUT_FD = os.dup(1)          # dup fd-1 → new fd
os.dup2(2, 1)                        # fd-1 now points to stderr

# keep a *binary* handle on the original stdin (fd-0) before any wrapper
_RAW_STDIN = sys.stdin.buffer

import json
import time
import traceback
from datetime import datetime
from pathlib import Path


# ── IPC helpers ──────────────────────────────────────────────────────
def _send(data: dict) -> None:
    """Write a single JSON object + newline to the *real* stdout pipe."""
    raw = json.dumps(data, ensure_ascii=False) + "\n"
    os.write(_REAL_STDOUT_FD, raw.encode("utf-8"))


def _recv() -> dict | None:
    """Block-read one line from raw stdin.  Returns parsed dict or None on EOF."""
    line = b""
    while True:
        ch = _RAW_STDIN.read(1)
        if not ch:                    # EOF → parent closed the pipe
            return None
        if ch == b"\n":
            break
        if ch == b"\r":
            continue                  # skip Windows CR
        line += ch
    text = line.decode("utf-8", errors="replace").strip()
    if not text:
        return {"_skip": True}        # blank line, not EOF
    return json.loads(text)


# Note: Using CPU-only llama-cpp-python v0.3.22 (supports Gemma 4 architecture)


# ── paths ────────────────────────────────────────────────────────────
APP_DATA     = Path(os.getenv("LOCALAPPDATA", "")) / "com.contextmemory.app"
DB_PATH      = APP_DATA / "lancedb"
MODELS_PATH  = APP_DATA / "models"
VISION_MODEL = MODELS_PATH / "gemma-4-vision.gguf"
PROJ_MODEL   = MODELS_PATH / "gemma-4-vision-mmproj.gguf"
CONFIG_PATH  = APP_DATA / "sync_config.json"

os.makedirs(DB_PATH, exist_ok=True)
os.makedirs(MODELS_PATH, exist_ok=True)

def _read_config():
    try:
        if CONFIG_PATH.exists():
            with open(CONFIG_PATH, "r") as f:
                return json.load(f)
    except Exception:
        pass
    return {}

def _write_config(data):
    try:
        with open(CONFIG_PATH, "w") as f:
            json.dump(data, f)
    except Exception:
        pass

def save_paired_ip(ip):
    cfg = _read_config()
    cfg["paired_ip"] = ip
    _write_config(cfg)

def get_paired_ip():
    return _read_config().get("paired_ip")

def set_auto_sync_enabled(enabled: bool):
    cfg = _read_config()
    cfg["auto_sync_enabled"] = enabled
    _write_config(cfg)

def get_auto_sync_enabled() -> bool:
    return _read_config().get("auto_sync_enabled", True)


# ── lazy-loaded AI manager ──────────────────────────────────────────
class AIManager:
    def __init__(self):
        self._embed  = None     # SentenceTransformer
        self._vision = None     # Llama
        self._table  = None     # LanceDB table
        self._use_gpu = False   # GPU acceleration toggle

    def set_gpu_mode(self, use_gpu: bool):
        """Toggle GPU mode. If changed, we unload the model to force reload on next use."""
        if self._use_gpu != use_gpu:
            self._use_gpu = use_gpu
            if self._vision is not None:
                import gc
                del self._vision
                self._vision = None
                gc.collect()

    # ── loaders ──────────────────────────────────────────────────────
    def _db(self):
        if self._table is None:
            import lancedb, pyarrow as pa
            db = lancedb.connect(str(DB_PATH))
            schema = pa.schema([
                pa.field("vector",     pa.list_(pa.float32(), 384)),
                pa.field("text",       pa.string()),
                pa.field("image_path", pa.string()),
                pa.field("timestamp",  pa.string()),
            ])
            # Try to open existing table, create if it doesn't exist
            try:
                self._table = db.open_table("context_memory")
            except Exception:
                self._table = db.create_table("context_memory", schema=schema)
        return self._table

    def _embedder(self):
        if self._embed is None:
            from sentence_transformers import SentenceTransformer
            self._embed = SentenceTransformer("all-MiniLM-L6-v2")
        return self._embed

    def _llm(self):
        """Load or return the Gemma4 model (always with CLIP handler)."""
        if self._vision is None:
            from llama_cpp import Llama
            if not VISION_MODEL.exists():
                raise FileNotFoundError(f"Model not found: {VISION_MODEL}")
            handler = None
            try:
                from llama_cpp.llama_chat_format import Llava15ChatHandler
                if PROJ_MODEL.exists():
                    handler = Llava15ChatHandler(clip_model_path=str(PROJ_MODEL))
            except ImportError:
                pass
            # Offload all layers to GPU if enabled (-1 is a shortcut for all)
            layers = -1 if self._use_gpu else 0
            
            # Use more threads for faster CPU inference
            import multiprocessing
            threads = multiprocessing.cpu_count()
            
            self._vision = Llama(
                model_path=str(VISION_MODEL),
                chat_handler=handler,
                n_ctx=4096,
                n_gpu_layers=layers,
                n_threads=threads,
                n_batch=512,
                verbose=False,
                use_mmap=True,
                use_mlock=True,
            )
        return self._vision

    # ── actions ──────────────────────────────────────────────────────
    ANALYSIS_PROMPT = (
        "You are a visual memory assistant that creates detailed, searchable records of screenshots. "
        "Analyze this screenshot thoroughly and produce a structured summary with ALL of the following sections:\n\n"
        "1. **Application & Context:** What application or website is shown? What is the user doing?\n"
        "2. **Visible Text:** Extract ALL readable text from the screen — titles, menus, labels, URLs, filenames, code, chat messages, etc.\n"
        "3. **UI Elements:** Describe key visual elements — buttons, tabs, sidebars, notifications, images, icons.\n"
        "4. **Content Summary:** What is the main topic or activity happening on screen? What information is being viewed or worked on?\n"
        "5. **Key Details:** Any specific names, numbers, dates, versions, scores, URLs, file paths, or other searchable facts.\n\n"
        "Be thorough and specific. Include every piece of text and detail you can identify. "
        "This description will be used to answer questions about the user's activity later."
    )

    def process_image(self, path: str) -> str:
        """Run vision model on a screenshot and store in LanceDB."""
        table = self._db()
        embedder = self._embedder()

        desc = ""
        try:
            llm = self._llm()
            import base64
            with open(path, "rb") as f:
                b64 = base64.b64encode(f.read()).decode()
            resp = llm.create_chat_completion(
                messages=[
                    {"role": "system", "content": self.ANALYSIS_PROMPT},
                    {"role": "user",
                     "content": [{"type": "image_url",
                                  "image_url": {"url": f"data:image/jpeg;base64,{b64}"}}]},
                ],
                max_tokens=1024,        # More tokens for thorough analysis
                repeat_penalty=1.2,
            )
            desc = resp["choices"][0]["message"]["content"]
        except Exception as exc:
            desc = f"Auto-description failed for {Path(path).name}: {exc}"

        vec = embedder.encode(desc).tolist()
        table.add([{
            "vector": vec,
            "text": desc,
            "image_path": str(path),
            "timestamp": datetime.now().isoformat(),
        }])
        return desc

    def search(self, query: str) -> list[dict]:
        table = self._db()
        vec = self._embedder().encode(query).tolist()
        rows = table.search(vec).limit(3).to_list()
        return [
            {"path": r["image_path"],
             "text": r["text"],
             "score": float(r.get("_distance", 0.0))}
            for r in rows
        ]

    def ask(self, query: str) -> dict:
        """Full RAG: search DB → use Gemma4 to generate answer from context."""
        results = self.search(query)
        if not results:
            return {"answer": "I couldn't find any relevant memories. Try taking some screenshots first!", "sources": []}

        ctx = "\n".join(
            f"- {r['text'][:800]}" for r in results if r['text'].strip()
        )
        if not ctx:
            return {"answer": "I found screenshots but they had no extractable text.", "sources": results}

        try:
            llm = self._llm()

            # Use chat completion for better instruction following
            messages = [
                {"role": "system", "content": "You are a helpful memory assistant. Answer the user's question using ONLY the provided memories. Be conversational and concise. If you don't know the answer, say so."},
                {"role": "user", "content": f"Here are my memories:\n{ctx}\n\nQuestion: {query}"}
            ]

            resp = llm.create_chat_completion(
                messages=messages,
                max_tokens=512,
                temperature=0.3,
                repeat_penalty=1.2,
                stream=True
            )
            
            full_answer = ""
            for chunk in resp:
                if "choices" in chunk and len(chunk["choices"]) > 0:
                    delta = chunk["choices"][0].get("delta", {})
                    if "content" in delta:
                        text = delta["content"]
                        full_answer += text
                        _send({
                            "status": "success", 
                            "action": "ask_chunk", 
                            "chunk": text,
                            "query": query
                        })
            
            answer = full_answer.strip()

        except Exception as exc:
            # Fallback: present the extracted memories directly (no LLM needed)
            try:
                llm.chat_handler = saved_handler
            except Exception:
                pass

            parts = []
            for i, r in enumerate(results, 1):
                t = r['text'].strip()
                if t:
                    parts.append(f"**Memory {i}:**\n{t[:600]}")
            if parts:
                answer = f"Here's what I found for \"{query}\":\n\n" + "\n\n".join(parts)
            else:
                answer = f"Error generating answer: {exc}"

        return {"answer": answer, "sources": results}


# ── main event loop ─────────────────────────────────────────────────
def main():
    mgr = AIManager()

    # Pre-initialize LanceDB and Embedder in the main thread to prevent GIL/import deadlocks in the background HTTP server
    try:
        mgr._db()
        mgr._embedder()
    except Exception as exc:
        import sys
        print(f"[AIManager] Failed to pre-init DB/Embedder: {exc}", file=sys.stderr)

    # Start the sync HTTP server in a background thread
    try:
        from sync_server import set_ai_manager, start_sync_server, get_local_ip, SYNC_PORT
        set_ai_manager(mgr)
        start_sync_server()
    except Exception as exc:
        import sys
        print(f"[SyncServer] Failed to import/start: {exc}", file=sys.stderr)

    while True:
        req = _recv()
        if req is None:            # stdin closed → exit
            break
        if req.get("_skip"):       # blank line, keep reading
            continue
        try:
            action = req.get("action", "")

            if action == "process":
                path = req["path"]
                desc = mgr.process_image(path)

                _send({"status": "success", "action": "process",
                       "path": path, "description": desc})

            elif action == "search":
                q = req["query"]
                _send({"status": "success", "action": "search",
                       "query": q, "results": mgr.search(q)})

            elif action == "get_all_memories":
                table = mgr._db()
                try:
                    rows = table.to_pandas()
                    memories = []
                    for _, row in rows.iterrows():
                        memories.append({
                            "timestamp": str(row.get("timestamp", "")),
                            "text": str(row.get("text", "")),
                            "image_path": str(row.get("image_path", ""))
                        })
                    # Sort by timestamp descending
                    memories.sort(key=lambda x: x["timestamp"], reverse=True)
                    _send({"status": "success", "action": "get_all_memories", "memories": memories})
                except Exception as e:
                    _send({"status": "error", "action": "get_all_memories", "message": str(e)})

            elif action == "delete_memory":
                timestamp = req.get("timestamp")
                if not timestamp:
                    _send({"status": "error", "action": "delete_memory", "message": "Missing timestamp"})
                    continue
                table = mgr._db()
                try:
                    # Escape quotes just in case, though ISO string shouldn't have them
                    ts_safe = timestamp.replace("'", "''")
                    table.delete(f"timestamp = '{ts_safe}'")
                    _send({"status": "success", "action": "delete_memory", "timestamp": timestamp})
                except Exception as e:
                    _send({"status": "error", "action": "delete_memory", "message": str(e)})

            elif action == "ask":
                q = req["query"]
                out = mgr.ask(q)
                _send({"status": "success", "action": "ask",
                       "query": q, "answer": out["answer"],
                       "sources": out["sources"]})

            elif action == "set_gpu_mode":
                use_gpu = req.get("use_gpu", False)
                mgr.set_gpu_mode(use_gpu)
                _send({"status": "success", "action": "set_gpu_mode", "use_gpu": use_gpu})

            elif action == "toggle_auto_sync":
                enabled = req.get("enabled", True)
                set_auto_sync_enabled(enabled)
                _send({"status": "success", "action": "toggle_auto_sync", "enabled": enabled})

            elif action == "sync_status":
                try:
                    ip = get_local_ip()
                    port = SYNC_PORT
                    paired_ip = get_paired_ip()
                except Exception:
                    ip = "unknown"
                    port = 8473
                    paired_ip = None
                count = 0
                try:
                    count = mgr._db().count_rows()
                except Exception:
                    pass
                _send({"status": "success", "action": "sync_status",
                       "ip": ip, "port": port, "entryCount": count,
                       "pairedIp": paired_ip,
                       "autoSyncEnabled": get_auto_sync_enabled()})

            elif action == "sync_with":
                target_ip = req.get("ip", "").strip()
                target_port = req.get("port", 8473)
                base_url = f"http://{target_ip}:{target_port}"

                import urllib.request, urllib.error

                try:
                    # 1. Pull remote entries
                    pull_req = urllib.request.Request(f"{base_url}/sync/entries")
                    with urllib.request.urlopen(pull_req, timeout=10) as resp:
                        remote_data = json.loads(resp.read().decode())

                    remote_entries = remote_data.get("entries", [])
                    table = mgr._db()
                    embedder = mgr._embedder()

                    # Get existing text keys for dedup
                    existing = set()
                    try:
                        rows = table.to_pandas()
                        for _, row in rows.iterrows():
                            existing.add(str(row.get("text", ""))[:80].lower())
                    except Exception:
                        pass

                    imported = 0
                    skipped = 0
                    for entry in remote_entries:
                        text = entry.get("text", "")
                        text_key = text[:80].lower()
                        if text_key in existing:
                            skipped += 1
                            continue
                        vec = embedder.encode(text).tolist()
                        table.add([{
                            "vector": vec,
                            "text": text,
                            "image_path": "",
                            "timestamp": datetime.now().isoformat(),
                        }])
                        existing.add(text_key)
                        imported += 1

                    # 2. Push local entries to remote
                    local_rows = table.to_pandas()
                    local_entries = []
                    for _, row in local_rows.iterrows():
                        ts_str = str(row.get("timestamp", ""))
                        try:
                            # Convert ISO string to epoch ms
                            ts_epoch = int(datetime.fromisoformat(ts_str).timestamp() * 1000)
                        except Exception:
                            ts_epoch = 0
                            
                        local_entries.append({
                            "text": str(row.get("text", "")),
                            "packageName": "pc",
                            "timestamp": ts_epoch,
                            "screenshotFilename": "",
                            "sourceDevice": "pc",
                        })

                    push_body = json.dumps({"entries": local_entries}).encode("utf-8")
                    push_req = urllib.request.Request(
                        f"{base_url}/sync/entries",
                        data=push_body,
                        headers={"Content-Type": "application/json"},
                        method="POST"
                    )
                    with urllib.request.urlopen(push_req, timeout=30) as resp:
                        push_result = json.loads(resp.read().decode())

                    exported = push_result.get("imported", 0)

                    # Pair successfully
                    save_paired_ip(ip)

                    _send({"status": "success", "action": "sync_with",
                           "imported": imported, "exported": exported,
                           "skipped": skipped})

                except urllib.error.URLError as e:
                    _send({"status": "error", "action": "sync_with",
                           "message": f"Cannot reach {base_url}: {e.reason}"})
                except Exception as e:
                    _send({"status": "error", "action": "sync_with",
                           "message": str(e)})

            else:
                _send({"status": "error",
                       "message": f"Unknown action: {action}"})

        except Exception:
            _send({"status": "error", "message": traceback.format_exc()})


if __name__ == "__main__":
    main()
