#!/bin/sh
# Mints a v3 onion service (the key is created on first start and kept in the private volume, so the address is
# stable across restarts) and forwards its port to ONION_TARGET. Only the PUBLIC half, the hostname, is copied to
# the shared volume, which is what the other services read; the secret key never leaves this container's volume.
#   ONION_TARGET   host:port the onion forwards to (required), e.g. web:8080
#   ONION_PORT     the port the onion listens on (default 80)
#   ONION_SOCKS    1 to also offer a SOCKS5 proxy on 9050 inside the compose network (outbound over Tor); default 0
set -eu
: "${ONION_TARGET:?set ONION_TARGET (host:port the onion forwards to)}"
port="${ONION_PORT:-80}"
socks=0
[ "${ONION_SOCKS:-0}" = 1 ] && socks="0.0.0.0:9050"
# Tor only accepts an IP address as an onion service's target, not a name: resolve the backend now, and again while
# running (a restarted container can get a new address), reloading Tor when it changes.
host="${ONION_TARGET%:*}"; tport="${ONION_TARGET##*:}"
resolve() { getent hosts "$host" | awk '{print $1; exit}'; }
write_torrc() {
  cat > /tmp/torrc <<TORRC
DataDirectory /var/lib/tor-hs/data
SocksPort $socks
ControlPort 0
Log notice stdout
HiddenServiceDir /var/lib/tor-hs/service
HiddenServiceVersion 3
HiddenServicePort $port $1:$tport
TORRC
}
# The onion address comes from the key, not the target, and the services behind it wait for that address before they
# start, so the backend may not exist yet: start with a placeholder (connections are refused until it does) and
# re-point as soon as the name resolves.
ip="$(resolve || true)"; [ -n "$ip" ] || ip=127.0.0.1
write_torrc "$ip"
mkdir -p /var/lib/tor-hs/data /var/lib/tor-hs/service
chmod 0700 /var/lib/tor-hs/data /var/lib/tor-hs/service
tor -f /tmp/torrc &
pid=$!
trap 'kill $pid 2>/dev/null; wait $pid 2>/dev/null; exit 0' TERM INT
(
  while kill -0 "$pid" 2>/dev/null; do
    sleep 5
    now="$(resolve || true)"
    if [ -n "$now" ] && [ "$now" != "$ip" ]; then
      ip="$now"; write_torrc "$ip"; kill -HUP "$pid" 2>/dev/null || true
      echo "backend $host moved to $ip: reloaded tor"
    fi
  done
) &
n=0
while [ ! -s /var/lib/tor-hs/service/hostname ]; do
  kill -0 "$pid" 2>/dev/null || { echo "tor exited before creating the onion service" >&2; exit 1; }
  n=$((n + 1)); [ "$n" -le 120 ] || { echo "no onion hostname after 120 s" >&2; exit 1; }
  sleep 1
done
cp /var/lib/tor-hs/service/hostname /onion-public/hostname.tmp
mv /onion-public/hostname.tmp /onion-public/hostname
echo "onion address: $(cat /onion-public/hostname)"
wait "$pid"
