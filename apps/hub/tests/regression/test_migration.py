import os,tempfile,sqlite3,importlib.util,pathlib
home=tempfile.mkdtemp(prefix='hubmig_')
os.environ['HOME']=home
os.environ['LOCALAPPDATA']=home
os.environ['APPDATA']=home
from app.legacy import runtime as h
assert pathlib.Path(home).resolve() in h.DB_PATH.resolve().parents, (home, h.DB_PATH)
# replace DB with v2.0.3-like schema and one row
if h.DB_PATH.exists(): h.DB_PATH.unlink()
con=sqlite3.connect(h.DB_PATH)
con.executescript('''
CREATE TABLE orders (id INTEGER PRIMARY KEY AUTOINCREMENT,platform TEXT NOT NULL,order_code TEXT NOT NULL DEFAULT '',short_order_number TEXT NOT NULL DEFAULT '',display_order_id TEXT NOT NULL DEFAULT '',full_order_id TEXT NOT NULL DEFAULT '',phone TEXT NOT NULL DEFAULT '',customer_name TEXT NOT NULL DEFAULT '',received_at TEXT NOT NULL,recorded_at TEXT NOT NULL,source_device TEXT NOT NULL DEFAULT '',sync_id TEXT NOT NULL DEFAULT '',dedup_key TEXT NOT NULL UNIQUE,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,manual_backup INTEGER NOT NULL DEFAULT 0,manual_backup_at TEXT NOT NULL DEFAULT '');
CREATE TABLE devices (source TEXT PRIMARY KEY,platform TEXT NOT NULL DEFAULT '',device_name TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT '',pending INTEGER NOT NULL DEFAULT 0,version TEXT NOT NULL DEFAULT '',last_seen TEXT NOT NULL,updated_at TEXT NOT NULL);
''')
now=h.local_now_iso(); con.execute("INSERT INTO orders(platform,order_code,received_at,recorded_at,dedup_key,created_at,updated_at,phone) VALUES(?,?,?,?,?,?,?,?)",('grab','GF-111',now,now,'grab:old',now,now,'0911111111')); con.commit(); con.close()
h.init_db()
with h.db_connect() as c:
 cols={r[1] for r in c.execute('PRAGMA table_info(orders)').fetchall()}; row=c.execute('SELECT * FROM orders WHERE order_code=?',('GF-111',)).fetchone(); audit=c.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='order_audit_log'").fetchone()
assert row and row['phone']=='0911111111'
assert {'deleted','deleted_at','deleted_by','delete_reason'} <= cols
assert audit
print('MIGRATION_PRESERVES_DATA_PASS')
