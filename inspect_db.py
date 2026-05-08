"""Inspect the ContextMemory LanceDB database."""
import lancedb
import os
from pathlib import Path

APP_DATA = Path(os.getenv('LOCALAPPDATA', '')) / 'com.contextmemory.app'
DB_PATH = APP_DATA / 'lancedb'

def inspect():
    if not DB_PATH.exists():
        print(f"[!] Database directory not found at {DB_PATH}")
        return

    db = lancedb.connect(str(DB_PATH))
    tables_result = db.list_tables()
    # list_tables() may return a paginated object, get actual names
    if hasattr(tables_result, 'tables'):
        tables = tables_result.tables
    elif isinstance(tables_result, list):
        tables = tables_result
    else:
        tables = list(tables_result)
    
    print(f"\n=== LanceDB at {DB_PATH} ===")
    print(f"    Tables: {tables}\n")
    
    if "context_memory" not in tables:
        print("[!] Table 'context_memory' does not exist yet.")
        print("    Take a screenshot with Alt+S while the app is running.")
        return
        
    table = db.open_table("context_memory")
    count = table.count_rows()
    print(f"--- Total Memories: {count} ---\n")
    
    if count == 0:
        print("[!] No memories stored yet. Take screenshots first!")
        return

    rows = table.to_pandas().head(10)
    
    for i, row in rows.iterrows():
        ts   = row.get('timestamp', 'N/A')
        text = row.get('text', '(empty)')
        path = row.get('image_path', '')
        print(f"  [{i+1}] Time: {ts}")
        print(f"      Path: {path}")
        safe_text = text[:300].encode('ascii', errors='replace').decode('ascii')
        print(f"      Text: {safe_text}")
        print()

if __name__ == "__main__":
    inspect()
