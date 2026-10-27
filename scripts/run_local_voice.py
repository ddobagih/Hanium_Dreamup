"""Run the local test voice server using ignored .env.voice-local settings."""
import os
from pathlib import Path
import sys

if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    settings = root / ".env.voice-local"
    for line in settings.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            os.environ[key.strip()] = value.strip()
    os.chdir(root)
    os.execv(sys.executable, [sys.executable, "-m", "uvicorn", "voice.server:app",
                            "--host", "127.0.0.1", "--port", "9001", "--workers", "1"])
