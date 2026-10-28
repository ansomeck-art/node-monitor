#!/usr/bin/env python3
"""Pair a reviewed endpoint using a token FILE; credential values are never logged."""
import argparse
import json
import os
import pathlib
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--token-file", type=pathlib.Path, required=True)
    parser.add_argument("--group", default="服务器")
    parser.add_argument("--allow-http", action="store_true")
    parser.add_argument("--widget", action="store_true")
    args = parser.parse_args()
    directory = ROOT / "data/mac"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    credential_dir = directory / "credentials"
    credential_dir.mkdir(exist_ok=True, mode=0o700)
    configuration_file = directory / "settings.json"
    config = json.loads(configuration_file.read_text()) if configuration_file.exists() else {
        "schemaVersion": 1, "hosts": [], "selectedHostID": None, "menuMetric": "summary", "energySaver": False,
        "appearance": "system", "notifications": False, "retentionDays": 7,
        "thresholds": {"enabled": True, "cpu": 90, "memory": 90, "cpuTemperature": 90, "gpuTemperature": 85,
                       "disk": 95, "durationSeconds": 60, "offlineSeconds": 60}
    }
    host = next((host for host in config["hosts"] if host["address"] == args.address), None)
    if host is None:
        host = {"id": str(uuid.uuid4()).upper(), "name": args.name, "group": args.group, "address": args.address,
                "allowHTTP": args.allow_http, "demo": False,
                "widget": {"visible": args.widget, "locked": False, "alwaysOnTop": False, "opacity": 0.94, "compact": False}}
        config["hosts"].append(host)
    secret = args.token_file.read_text().strip()
    if len(secret) < 32:
        raise SystemExit("Invalid token file; no credential content was printed.")
    destination = credential_dir / (host["id"] + ".token")
    with os.fdopen(os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as stream:
        stream.write(secret + "\n")
    os.chmod(destination, 0o600)
    config["selectedHostID"] = host["id"]
    temporary = directory / "settings.new.json"
    temporary.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    os.chmod(temporary, 0o600)
    temporary.replace(configuration_file)
    if args.token_file.resolve() != destination.resolve() and args.token_file.resolve().is_relative_to(credential_dir.resolve()):
        args.token_file.unlink()
    print("Paired endpoint. Credential value was not displayed. Restart the app to load the configuration.")

if __name__ == "__main__":
    main()
