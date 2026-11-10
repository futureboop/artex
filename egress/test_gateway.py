import importlib.util
import ipaddress
import json
import pathlib
import socket
import threading
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("gateway", pathlib.Path(__file__).with_name("gateway.py"))
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class GatewayTests(unittest.TestCase):
    def test_proxy_url_requires_literal_ipv4_and_socks5(self):
        host, port, user, password = gateway.parse_proxy(
            "socks5://u%40x:p%3Aa@203.0.113.10:1080"
        )
        self.assertEqual((str(host), port, user, password), ("203.0.113.10", 1080, "u@x", "p:a"))
        for value in (
            "http://203.0.113.10:1080",
            "socks5://proxy.example:1080",
            "socks5://203.0.113.10",
            "socks5://u@203.0.113.10:1080",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                gateway.parse_proxy(value)

    def test_udp_associate_is_required_and_relay_is_pinned(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]

        def server():
            try:
                with listener.accept()[0] as conn:
                    self.assertEqual(conn.recv(3), b"\x05\x01\x00")
                    conn.sendall(b"\x05\x00")
                    self.assertEqual(conn.recv(10), b"\x05\x03\x00\x01" + b"\x00" * 6)
                    conn.sendall(b"\x05\x00\x00\x01" + socket.inet_aton("198.51.100.7") + b"\x23\x45")
            finally:
                listener.close()

        worker = threading.Thread(target=server)
        worker.start()
        relay = gateway.udp_relay_address(ipaddress.IPv4Address("127.0.0.1"), port, "", "")
        worker.join(timeout=2)
        self.assertEqual(str(relay), "198.51.100.7")

    def test_kill_switch_only_allows_tunnel_and_upstream(self):
        with patch.object(gateway.subprocess, "run") as run:
            gateway.install_kill_switch(
                ipaddress.IPv4Address("203.0.113.10"),
                1080,
                ipaddress.IPv4Address("198.51.100.7"),
                "eth0",
            )
        rules = run.call_args.kwargs["input"]
        self.assertIn("policy drop", rules)
        self.assertIn('oifname "tun0" meta l4proto { tcp, udp } accept', rules)
        self.assertIn('ip daddr 203.0.113.10 tcp dport 1080 accept', rules)
        self.assertIn('ip daddr 198.51.100.7 meta l4proto udp accept', rules)
        self.assertIn('ip daddr 127.0.0.11 udp dport 53 reject', rules)

    def test_config_only_routes_via_socks_and_rejects_icmp(self):
        config = gateway.sing_box_config(ipaddress.IPv4Address("203.0.113.10"), 1080, "u", "p")
        self.assertEqual(config["route"]["final"], "proxy")
        self.assertEqual(config["route"]["rules"], [{"network": "icmp", "action": "reject"}])
        self.assertEqual(config["outbounds"][0]["type"], "socks")
        self.assertEqual(config["inbounds"][0]["strict_route"], True)
        json.dumps(config)


if __name__ == "__main__":
    unittest.main()
