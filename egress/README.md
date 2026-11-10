# ARTEX transparent TCP/UDP egress

This is an **opt-in** Docker Compose deployment. ARTEX and every command it
starts share the `egress` container's network namespace. The upstream SOCKS5
URL is only passed to that gateway container, not to ARTEX or its agents.
The gateway's nftables output chain denies new physical egress except the
SOCKS5 control connection, the SOCKS5 UDP relay, and PostgreSQL. TCP/UDP
traffic to other destinations can leave only on the TUN interface. ICMP and
other IP protocols are dropped. Raw packet tools that require `NET_RAW` (such
as SYN scans) are unavailable; ordinary TCP connections and UDP sockets work.
A stopped or unreachable proxy never causes a
direct fallback.

## Requirements

- Linux Docker host with `/dev/net/tun`, `NET_ADMIN`, nftables, and a SOCKS5
  server that accepts `UDP ASSOCIATE`. HTTP proxies and SOCKS4 are rejected.
- The proxy URL must use an IPv4 literal to avoid bootstrapping DNS outside
  the tunnel. URL-encode any reserved characters in the username or password.
- The fixed Docker subnets `172.28.223.0/24` and `172.28.224.0/24` must be
  free on this host. Adjust both subnets, their static addresses,
  `ARTEX_EGRESS_DB_IP`, `ARTEX_EGRESS_LOCAL_SUBNETS`, and the Nginx upstream
  address together if either conflicts with another network.

Add to the untracked `.env` beside `compose.egress.yml`:

```dotenv
POSTGRES_PASSWORD=your_existing_database_password
ARTEX_IMAGE=your_existing_artex_image:tag
ARTEX_PUBLIC_PORT=8787
ARTEX_EGRESS_SOCKS5=socks5://user:password@203.0.113.10:1080
```

For a new installation, omit `ARTEX_IMAGE` to use the default image. For an
existing installation, retain its current image tag, `./data` directory, and
the same Compose project name/`pgdata` volume. Back up the database and data
directory before switching Compose files. The transparent proxy is configured
by `ARTEX_EGRESS_SOCKS5`; the web UI's **Global proxy** setting is a separate
application-level HTTP proxy and should be left empty in this deployment.

```sh
docker compose -f compose.egress.yml config --quiet
docker compose -f compose.egress.yml up -d --build
docker compose -f compose.egress.yml ps
```

If the SOCKS5 server reports a UDP relay address different from the control
server, the gateway learns that IP during startup. If the server reports a
relay DNS name, set `ARTEX_EGRESS_UDP_RELAY_IP` to its verified IPv4 address.
The gateway permits UDP only to that relay IP. An upstream that refuses UDP
ASSOCIATE prevents ARTEX from starting in this mode.

## Verification

Run these from the ARTEX container. The first three must show the proxy's
outbound address, including the `--noproxy` attempt. The fourth must fail:

```sh
docker compose -f compose.egress.yml exec artex curl -4fsS https://api.ipify.org
docker compose -f compose.egress.yml exec artex curl --noproxy '*' -4fsS https://api.ipify.org
docker compose -f compose.egress.yml exec artex python3 -c 'import urllib.request; print(urllib.request.urlopen("https://api.ipify.org", timeout=10).read().decode())'
docker compose -f compose.egress.yml exec artex ping -c 1 1.1.1.1
```

Verify UDP through a destination
you control (for example, a DNS server reachable through the SOCKS5 UDP
relay):

```sh
docker compose -f compose.egress.yml exec artex dig @1.1.1.1 example.com
```

Also test with the upstream proxy stopped: TCP and UDP requests must fail,
while the ARTEX UI and local PostgreSQL connection remain available. PostgreSQL
has only an internal Docker network and cannot use its own direct Internet
route. DNS for
external names is sent to a DoH resolver through the SOCKS5 proxy. The ARTEX
container's `/etc/resolv.conf` is mounted from `egress/resolv.conf` so Docker's
embedded resolver cannot resolve external names directly.

This routes traffic through the configured proxy; it does not limit which
remote target the agent chooses. Enforce an authorized target allowlist
separately if task scope requires it.
