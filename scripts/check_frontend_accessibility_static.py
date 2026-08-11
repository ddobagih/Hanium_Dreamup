#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> int:
    print(f"FAIL: {message}")
    return 1


def main() -> int:
    layout = ROOT / "apps/web/app/layout.tsx"
    page = ROOT / "apps/web/app/page.tsx"
    assist = ROOT / "apps/web/app/_walksafe/components/AssistPanel.tsx"
    camera = ROOT / "apps/web/app/_walksafe/components/CameraSurface.tsx"
    css = ROOT / "apps/web/app/globals.css"
    pwa_hook = ROOT / "apps/web/app/_walksafe/hooks/usePwaStatus.ts"
    manifest = ROOT / "apps/web/public/manifest.webmanifest"
    sw = ROOT / "apps/web/public/sw.js"
    icon_192 = ROOT / "apps/web/public/icons/icon-192.png"
    icon_512 = ROOT / "apps/web/public/icons/icon-512.png"

    layout_text = layout.read_text(encoding="utf-8")
    page_text = page.read_text(encoding="utf-8")
    assist_text = assist.read_text(encoding="utf-8")
    camera_text = camera.read_text(encoding="utf-8")
    css_text = css.read_text(encoding="utf-8")
    pwa_hook_text = pwa_hook.read_text(encoding="utf-8")
    manifest_text = manifest.read_text(encoding="utf-8")
    sw_text = sw.read_text(encoding="utf-8")

    checks = [
        ("maximumScale" not in layout_text, "viewport.maximumScale must not disable zoom"),
        ("skip-link" in page_text and "skip-link" in css_text, "skip link must be present and styled"),
        ('aria-live="polite"' in assist_text, "AssistPanel should expose polite live regions"),
        ('aria-hidden="true"' in camera_text, "camera bbox overlays should be aria-hidden"),
        ("min-height: 48px" in css_text or "min-height: 52px" in css_text, "touch targets should include >=48px policy"),
        ('"id": "/"' in manifest_text and '"scope": "/"' in manifest_text and '"lang": "ko-KR"' in manifest_text, "PWA manifest id/scope/lang are required"),
        (
            '"/icons/icon-192.png"' in manifest_text
            and '"/icons/icon-512.png"' in manifest_text
            and icon_192.is_file()
            and icon_512.is_file(),
            "PWA manifest should include 192/512 PNG maskable icons",
        ),
        ("cache: \"no-store\"" in sw_text and "API_PATH_PREFIXES" in sw_text, "service worker must not cache API/upload responses"),
        ("beforeinstallprompt" in pwa_hook_text and "appinstalled" in pwa_hook_text, "PWA install prompt state must be handled"),
        ("SW_VERSION" in sw_text and "SKIP_WAITING" in sw_text, "service worker version/update messages are required"),
        ("caches.match(\"/\")" in sw_text, "service worker should provide cached shell navigation fallback"),
    ]
    for ok, message in checks:
        if not ok:
            return fail(message)
    print("PASS: frontend accessibility/PWA static checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
