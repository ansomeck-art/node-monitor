#!/usr/bin/env python3
"""Read-only SSH banner discovery over the current local subnet; never authenticates."""
import concurrent.futures
import ipaddress
import json
import pathlib
import re
import socket
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent

def inspect(address):
    try:
        with socket.create_connection((address, 22), timeout=0.7) as connection:
            connection.settimeout(0.8)
            banner = connection.recv(512).decode("ascii", errors="replace").strip().splitlines()[0]
        if banner.startswith("SSH-"):
            return {"address": address, "sshBanner": banner[:200], "ubuntuHint": "ubuntu" in banner.lower()}
    except (OSError, IndexError):
        return None

def main():
    route = subprocess.check_output(["/sbin/route", "-n", "get", "default"], text=True)
    interface = re.search(r"interface:\s*(\S+)", route).group(1)
    address = subprocess.check_output(["/usr/sbin/ipconfig", "getifaddr", interface], text=True).strip()
    mask = subprocess.check_output(["/usr/sbin/ipconfig", "getoption", interface, "subnet_mask"], text=True).strip()
    network = ipaddress.ip_network(address + "/" + mask, strict=False)
    if not network.is_private or network.num_addresses > 256:
        raise SystemExit("Discovery is limited to a private subnet of at most 256 addresses.")
    candidates = [str(value) for value in network.hosts() if str(value) != address]
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        results = [result for result in pool.map(inspect, candidates) if result]
    report = {"network": str(network), "localAddress": address, "sshCandidates": results}
    directory = ROOT / ".cache"
    directory.mkdir(exist_ok=True)
    (directory / "lan-discovery.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
