# nbbridge — NetBird gomobile bindings

## Overview

`nbbridge` exposes a NetBird tunnel backend to Haven's Kotlin code via
gomobile, matching the public API shape of `wgbridge` and `tsbridge`:

```
StartTunnel(...) → TunnelHandle → Dial → Conn → Read/Write/Close
```

## How gomobile bindings work here

gomobile compiles Go packages into a Kotlin/Android library. All types
in the Go package that are exported (capitalized) become Kotlin classes.
No `.kt` source files are needed in this directory — the Go structs
**are** the binding definitions.

## Stub types: NativeTunAdapter & NativeIFaceDiscover

```go
type NativeTunAdapter struct { handle int64 }
type NativeIFaceDiscover struct { handle int64 }
```

These are **Go-side stubs** passed as parameters to `StartTunnel`. They
exist for API parity with the task spec and with other bridge backends.

### Why they are stubs

The current implementation uses `embed.Client` in **userspace netstack
mode** (`NB_USE_NETSTACK_MODE=true`). In this mode:

- The TUN device is created by the userspace stack internally
- Interface discovery falls back to a no-op (relay-only ICE)
- The Kotlin-side adapter objects are not wired into the data path

Both parameters are accepted but discarded (`_ = tunAdapter`,
`_ = ifaceDiscover`).

## Future: P2P ICE wiring

When direct peer-to-peer ICE connectivity is needed (bypassing relay
servers), these stubs will be fleshed out:

- **NativeTunAdapter** — Kotlin class backed by Android's
  `VpnService.Builder` / `ParcelFileDescriptor`. The Go side would call
  through gomobile to hand the TUN fd to the OS network stack instead
  of using netstack.

- **NativeIFaceDiscover** — Kotlin class that enumerates network
  interfaces via Android's `ConnectivityManager` and passes them to
  Go through a gomobile callback. Enables ICE candidate gathering on
  real device interfaces (Wi-Fi, cellular) rather than relying on
  relay-only fallback.

The `StartTunnel` signature already accepts these parameters, so the
Kotlin side can be updated without changing the Go API.

## See also

- `nbbridge.go` — full implementation
- `../wgbridge/wgbridge.go` — WireGuard backend (same API shape)
- `../tsbridge/tsbridge.go` — Tailscale backend (same API shape)
