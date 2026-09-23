from pathlib import Path

def load_dashboard_html() -> str:
    path = Path(__file__).resolve().parent / "templates" / "dashboard.html"
    return path.read_text(encoding="utf-8")
