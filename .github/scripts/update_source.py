"""SideStore / AltStore 用のソース (source.json) に新しいバージョンを追加する。

使い方: python3 update_source.py <version> <build> <ipa_path> <download_url>
"""
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

version, build, ipa, url = sys.argv[1:5]
repo = os.environ.get("GITHUB_REPOSITORY", "FuyunoNatsuki/kc-for-ios")
owner = repo.split("/")[0]
raw = f"https://raw.githubusercontent.com/{repo}/main"
path = Path("source.json")
keep = 10  # 履歴として残すバージョン数

if path.exists():
    source = json.loads(path.read_text(encoding="utf-8"))
else:
    source = {
        "name": "艦ブラ",
        "identifier": f"io.github.{owner.lower()}.kc-for-ios",
        "subtitle": "iPhone用 艦これ専用ブラウザ",
        "iconURL": f"{raw}/docs/icon.png",
        "website": f"https://github.com/{repo}",
        "apps": [],
        "news": [],
    }

if not source["apps"]:
    source["apps"].append({
        "name": "艦ブラ",
        "bundleIdentifier": "com.example.kanbrowser",
        "developerName": owner,
        "subtitle": "艦これを快適に遊ぶための専用ブラウザ",
        "localizedDescription": (
            "DMM版「艦隊これくしょん」をiPhoneで遊ぶための専用ブラウザです。\n"
            "ゲーム画面のフィット表示、艦隊の疲労・損傷、遠征・入渠・建造の残り時間、"
            "受注中の任務を表示する情報パネル、完了通知に対応しています。"
        ),
        "iconURL": f"{raw}/docs/icon.png",
        "tintColor": "1F4E79",
        "category": "games",
        "screenshots": [],
        "appPermissions": {
            "entitlements": [],
            "privacy": {
                "NSPhotoLibraryAddUsageDescription": "スクリーンショットを写真に保存するために使用します",
            },
        },
        "versions": [],
    })

app = source["apps"][0]
entry = {
    "version": version,
    "buildVersion": build,
    "date": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "localizedDescription": (os.environ.get("COMMIT_MESSAGE", "").strip().splitlines() or ["更新"])[0],
    "downloadURL": url,
    "size": os.path.getsize(ipa),
    "minOSVersion": "16.0",
}
app["versions"] = [entry] + [v for v in app["versions"] if v.get("version") != version][: keep - 1]

# 旧形式のクライアント向け
for key in ("version", "versionDate", "downloadURL", "size"):
    app.pop(key, None)
app.update({"version": version, "versionDate": entry["date"], "downloadURL": url, "size": entry["size"]})

path.write_text(json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"source.json: {version} ({build}) -> {url}")
