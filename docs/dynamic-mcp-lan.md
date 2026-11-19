# Dynamic MCP registry and private-LAN sharing

The Root edition keeps one daemon-owned MCP facade and exposes it through three transports:

- The existing loopback listener (default port 18766).
- The configured OpenAI Secure Tunnel.
- An optional private-LAN listener (default port 18767).

Every transport uses the same dynamic registry. Adding, enabling, disabling, refreshing, or removing an upstream MCP server updates the aggregate catalog without maintaining separate tool lists.

## Enable private-LAN sharing

Open **Agent connections → MCP servers** and use the **Share MCP tools on your local network** card.

- LAN sharing is disabled by default.
- The default port is 18767; choose an unused port from 1024 through 65535.
- The daemon binds IPv4 interfaces only while enabled.
- The UI displays a private IPv4 endpoint when a LAN route is available.
- Use **Show access token** to reveal the independent LAN bearer token. **Rotate access token** invalidates the old token immediately.
- Disabling the feature closes the listener. The setting and token are stored in the daemon's root-only settings directory.

Third-party MCP clients should connect to `http://<tablet-lan-ip>:18767/mcp` and send:

```http
Authorization: Bearer <LAN bearer token>
```

Use a Streamable HTTP MCP client. Browser-only clients are not supported because the listener does not enable cross-origin browser access.

## Network protections

The LAN listener is separate from the loopback listener and the OpenAI tunnel:

- It is opt-in and is never started by default.
- It has its own random 256-bit bearer token.
- It accepts peers only from loopback, RFC1918 IPv4, IPv4 link-local, IPv6 ULA, and IPv6 link-local ranges.
- Public peers are dropped before HTTP parsing.
- The same MCP facade enforces protocol validation, request-size bounds, timeouts, and connection limits.
- The listener does not provide CORS and should not be port-forwarded from a router.

A private address is not a trust boundary by itself. Keep the token private and use the feature only on networks whose clients you trust.

## Android permission facts

The daemon periodically refreshes Android permission facts for the frontend package:

- On API 37+, local-network access is checked against `android.permission.ACCESS_LOCAL_NETWORK`; earlier Android versions use a socket-bind probe.
- Notification-listener access is read from Android's enabled notification-listener setting.
- Accessibility state is read from Android's enabled accessibility-service setting.
- MediaProjection is reported active only while the user's consented projection session is active.

Notification access and accessibility are opt-in special access. MediaProjection always requires a fresh Android system consent prompt. The notification listener does not persist notification contents; the accessibility service does not retrieve window text. MediaProjection is held in a foreground service while active.

These Android permissions are distinct from the Root daemon's Magisk capabilities. A permission being enabled does not by itself imply that every agent tool has an executor for that API.
