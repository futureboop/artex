#!/usr/bin/env python3
"""Set up a fail-closed network namespace before ARTEX joins it.

Only this gateway container has NET_ADMIN. The ARTEX container shares its
network namespace but neither its filesystem nor its proxy credentials.
"""

import ipaddress
import json
import os
import re
import socket
import subprocess
import sys
from urllib.parse import unquote, urlsplit


DB_IP = ipaddress.IPv4Address(os.environ.get("ARTEX_EGRESS_DB_IP", "172.28.224.2"))
LOCAL_SUBNETS = [
    ipaddress.IPv4Network(value.strip())
    for value in os.environ.get(
        "ARTEX_EGRESS_LOCAL_SUBNETS", "172.28.223.0/24,172.28.224.0/24"
    ).split(",")
]
CONFIG_PATH = "/run/artex-egress/config.json"


def read_exact(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise RuntimeError("SOCKS5 server closed the connection")
        data.extend(chunk)
    return bytes(data)


def parse_proxy(raw):
    parsed = urlsplit(raw)
    if parsed.scheme not in ("socks5", "socks5h"):
        raise ValueError("transparent TCP/UDP mode requires a SOCKS5 proxy")
    if parsed.query or parsed.fragment or parsed.path:
        raise ValueError("SOCKS5 URL must not contain a path, query, or fragment")
    try:
        host = ipaddress.IPv4Address(parsed.hostname or "")
        port = parsed.port
    except (ValueError, TypeError) as exc:
        raise ValueError("SOCKS5 host must be an IPv4 literal and port must be valid") from exc
    if port is None:
        raise ValueError("SOCKS5 URL must include a port")
    username = unquote(parsed.username or "")
    password = unquote(parsed.password or "")
    if bool(username) != bool(password):
        raise ValueError("SOCKS5 username and password must both be set")
    if len(username.encode()) > 255 or len(password.encode()) > 255:
        raise ValueError("SOCKS5 credentials must be at most 255 bytes each")
    return host, port, username, password


def udp_relay_address(host, port, username, password):
    """Require UDP ASSOCIATE, then learn the relay IP for the kill switch."""
    with socket.create_connection((str(host), port), timeout=8) as sock:
        sock.settimeout(8)
        method = 2 if username else 0
        sock.sendall(bytes((5, 1, method)))
        if read_exact(sock, 2) != bytes((5, method)):
            raise RuntimeError("SOCKS5 server does not accept the configured authentication")
        if method == 2:
            user = username.encode()
            secret = password.encode()
            sock.sendall(bytes((1, len(user))) + user + bytes((len(secret),)) + secret)
            if read_exact(sock, 2) != b"\x01\x00":
                raise RuntimeError("SOCKS5 authentication failed")
        sock.sendall(b"\x05\x03\x00\x01" + b"\x00" * 6)
        version, status, reserved, atyp = read_exact(sock, 4)
        if (version, status, reserved) != (5, 0, 0):
            raise RuntimeError(f"SOCKS5 UDP ASSOCIATE failed (status {status})")
        if atyp == 1:
            relay = ipaddress.IPv4Address(read_exact(sock, 4))
        elif atyp == 3:
            read_exact(sock, read_exact(sock, 1)[0])
            relay = None
        elif atyp == 4:
            read_exact(sock, 16)
            relay = None
        else:
            raise RuntimeError(f"SOCKS5 returned unsupported relay address type {atyp}")
        read_exact(sock, 2)  # relay port varies by association; the IP is pinned.
        return host if relay is not None and relay.is_unspecified else relay


def sing_box_config(host, port, username, password):
    outbound = {
        "type": "socks",
        "tag": "proxy",
        "server": str(host),
        "server_port": port,
        "version": "5",
    }
    if username:
        outbound.update(username=username, password=password)
    return {
        "log": {"level": "warn"},
        "dns": {
            "servers": [
                {"type": "https", "tag": "remote-dns", "server": "1.1.1.1", "detour": "proxy"}
            ],
            "final": "remote-dns",
        },
        "inbounds": [
            {
                "type": "tun",
                "tag": "transparent",
                "interface_name": "tun0",
                "address": ["198.18.0.1/30", "fdfe:dcba:9876::1/126"],
                "dns_address": ["198.18.0.2", "fdfe:dcba:9876::2"],
                "dns_mode": "hijack",
                "auto_route": True,
                "auto_redirect": True,
                "strict_route": True,
                "route_exclude_address": [str(subnet) for subnet in LOCAL_SUBNETS],
            }
        ],
        "outbounds": [outbound],
        "route": {
            "auto_detect_interface": True,
            "rules": [{"network": "icmp", "action": "reject"}],
            "final": "proxy",
        },
    }


def detect_wan_interface(host):
    route = subprocess.check_output(["ip", "-4", "route", "get", str(host)], text=True)
    match = re.search(r"\bdev\s+([A-Za-z0-9_.:-]+)", route)
    if not match:
        raise RuntimeError("cannot identify the proxy-facing network interface")
    return match.group(1)


def install_kill_switch(host, port, relay, wan_interface):
    # The only physical egress is the SOCKS control endpoint and its UDP relay.
    # All other new outbound TCP/UDP must leave through tun0. IPv6 physical
    # egress and unsupported protocols have no matching rule and are dropped.
    rules = f"""
table inet artex_egress {{
  chain output {{
    # sing-box's auto_redirect output hooks run at mangle+1/+2; filter later.
    type filter hook output priority 10; policy drop;
    ct state established,related accept
    ct status dnat meta l4proto tcp accept
    meta mark 0x2023 meta l4proto udp accept
    oifname "lo" ip daddr 127.0.0.11 udp dport 53 reject
    oifname "lo" ip daddr 127.0.0.11 tcp dport 53 reject
    oifname "lo" accept
    ip daddr {DB_IP} tcp dport 5432 accept
    oifname "{wan_interface}" ip daddr {host} tcp dport {port} accept
    oifname "{wan_interface}" ip daddr {relay} meta l4proto udp accept
    oifname "tun0" meta l4proto {{ tcp, udp }} accept
  }}
}}
"""
    subprocess.run(["nft", "-f", "-"], input=rules, text=True, check=True)


def main():
    host, port, username, password = parse_proxy(os.environ["ARTEX_EGRESS_SOCKS5"])
    relay = udp_relay_address(host, port, username, password)
    override = os.environ.get("ARTEX_EGRESS_UDP_RELAY_IP", "").strip()
    if override:
        relay = ipaddress.IPv4Address(override)
    if relay is None:
        raise ValueError("SOCKS5 returned a non-IPv4 UDP relay; set ARTEX_EGRESS_UDP_RELAY_IP")
    os.makedirs(os.path.dirname(CONFIG_PATH), mode=0o700, exist_ok=True)
    fd = os.open(CONFIG_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as config_file:
        json.dump(sing_box_config(host, port, username, password), config_file)
    subprocess.run(["sing-box", "check", "-c", CONFIG_PATH], check=True)
    install_kill_switch(host, port, relay, detect_wan_interface(host))
    print("[egress] kill switch active; SOCKS5 TCP/UDP relay pinned", flush=True)
    os.execvp("sing-box", ["sing-box", "run", "-c", CONFIG_PATH])


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as exc:
        print(f"[egress] startup failed: {exc}", file=sys.stderr)
        sys.exit(1)
