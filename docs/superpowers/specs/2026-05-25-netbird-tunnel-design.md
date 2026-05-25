# NetBird Tunnel Integration Design

**Date:** 2026-05-25
**Status:** Approved
**Author:** opencode

## Problem Statement

Add NetBird as a third tunnel type alongside existing WireGuard and Tailscale implementations in Haven, enabling per-app WireGuard-based mesh networking via NetBird's management plane.

## User Intent

User requested: "이미있는 wireguard, tailscale 구현을 참고해서 NetBird 구현도 추가" (Add NetBird implementation referencing existing WireGuard and Tailscale implementations)

User chose **Approach 1: Full NetBird client** — a gomobile bridge wrapping NetBird's Go Engine, matching the Tailscale tsbridge pattern. NOT a WireGuard config passthrough.

## Architecture

### Existing Pattern (Reference)

Haven already has two tunnel implementations following a consistent pattern:

```
Kotlin Tunnel (implements Tunnel interface)
    │ JNI
    ▼
Go bridge (wgbridge / tsbridge)
    │
    ▼
Backend (wireguard-go / tsnet.Server)
```

- **wgbridge**: Parses wg-quick config, creates `netstack.CreateNetTUN`, wires up `device.Device`
- **tsbridge**: Wraps `tsnet.Server` with authkey + state dir, blocks on `Up()` + peer-map sync
- Both expose identical gomobile API: `StartTunnel() → TunnelHandle → Dial/ListenUDP/StartSocksListener/ListenTCP/Close`

### NetBird Architecture

NetBird's client has a `ConnectClient` with `RunOnAndroid()` that accepts `MobileDependency`:

```go
type MobileDependency struct {
    TunAdapter            device.TunAdapter       // TUN device configuration
    IFaceDiscover         stdnet.ExternalIFaceDiscover  // Network interface discovery
    NetworkChangeListener listener.NetworkChangeListener
    HostDNSAddresses      []netip.AddrPort
    DnsReadyListener      dns.ReadyListener
    StateFilePath         string
    TempDir               string
}
```

The `TunAdapter` interface:
```go
type TunAdapter interface {
    ConfigureInterface(address, addressV6 string, mtu int, dns, searchDomains, routes string) (int, error)
    UpdateAddr(address string) error
    ProtectSocket(fd int32) bool
}
```

Key insight: NetBird's Engine manages its own WireGuard device. On Android, the `TunAdapter` lets the Engine configure the TUN device while Kotlin's VpnService owns the actual fd.

### Proposed Architecture

```
┌─────────────────────────────────────────────────────┐
│  Kotlin (Android)                                    │
│  ┌─────────────────┐  ┌──────────────────────────┐  │
│  │ NetBirdTunnel   │  │ AndroidTunAdapter        │  │
│  │ (Tunnel impl)   │◄─┤ (implements TunAdapter)  │  │
│  │ - stateDir      │  │ - configures VpnService  │  │
│  │ - native handle │  │ - protects sockets       │  │
│  └────────┬────────┘  └────────────┬─────────────┘  │
│           │ JNI                    │ JNI             │
├───────────┼────────────────────────┼─────────────────┤
│  Go       ▼                        ▼                 │
│  ┌─────────────────────────────────────────────────┐│
│  │  nbbridge                                        ││
│  │  ┌─────────────┐  ┌──────────────────────────┐  ││
│  │  │ TunnelHandle│  │ gomobileTunAdapter       │  ││
│  │  │ (dial/udp)  │  │ (concrete TunAdapter     │  ││
│  │  │ - Connect   │  │  backed by Kotlin cb)    │  ││
│  │  │ - netstack  │  └────────────┬─────────────┘  ││
│  │  └──────┬──────┘               │                ││
│  │         │                      │                ││
│  │  ┌──────▼──────────────────────▼──────────────┐ ││
│  │  │  NetBird ConnectClient + Engine            │ ││
│  │  │  - management gRPC (login, sync)           │ ││
│  │  │  - signal gRPC (ICE negotiation)           │ ││
│  │  │  - WireGuard device (userspace)            │ ││
│  │  │  - ICE/STUN/TURN/Relay                     │ ││
│  │  └────────────────────────────────────────────┘ ││
│  └──────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────┘
```

## Implementation Details

### 1. Go Bridge: `rclone-android/go/nbbridge/nbbridge.go`

**TunnelHandle struct:**
```go
type TunnelHandle struct {
    client     *internal.ConnectClient
    engine     *internal.Engine
    tnet       *netstack.Net       // From Engine's WGIface
    mu         sync.Mutex
    closed     bool
    socksLn    net.Listener
    bindAddr   netip.Addr
}
```

**StartTunnel function:**
```go
func StartTunnel(managementURL, setupKey, hostname, stateDir string,
    tunAdapter TunAdapter, ifaceDiscover IFaceDiscover) (*TunnelHandle, error)
```

Flow:
1. Create `profilemanager.Config` with management URL, setup key, hostname
2. Generate WireGuard keypair (stored in state dir for persistence)
3. Create `ConnectClient` with config
4. Set `androidRunOverride` to inject `MobileDependency` with `TunAdapter`
5. Call `client.Run()` in a goroutine
6. Wait for engine to start (channel signal)
7. Extract netstack from Engine's WGIface for dial operations
8. Return TunnelHandle

**Methods (matching wgbridge/tsbridge API):**
- `Dial(host, port, timeoutMs) → Conn`
- `ListenUDP() → UDPConn`
- `StartSocksListener() → int` (port)
- `ListenTCP(port) → Listener`
- `BindAddr() → string`
- `Close()`

**gomobile TunAdapter bridging:**
gomobile cannot bind Go interfaces, but CAN expose Kotlin classes to Go. The approach:

1. Define a Kotlin class `NativeTunAdapter` with methods matching `TunAdapter` interface
2. gomobile generates Go bindings for this Kotlin class
3. Go's `nbbridge` accepts a `*NativeTunAdapter` parameter and calls its methods directly
4. No Go-side interface wrapper needed — the gomobile-bound Kotlin class IS the adapter

```kotlin
// Kotlin — gomobile exposes this to Go
class NativeTunAdapter(
    private val vpnService: VpnService,
    private val builder: VpnService.Builder,
) {
    fun configureInterface(address: String, addressV6: String,
        mtu: Int, dns: String, searchDomains: String, routes: String): Long {
        // Configure builder, establish TUN, return fd
    }
    fun updateAddr(address: String) { /* update VpnService */ }
    fun protectSocket(fd: Int): Boolean = vpnService.protect(fd)
}
```

```go
// Go — receives gomobile-bound Kotlin object
func StartTunnel(managementURL, setupKey, hostname, stateDir string,
    tunAdapter *NativeTunAdapter, ifaceDiscover *NativeIFaceDiscover) (*TunnelHandle, error) {
    // Wrap in Go interface for NetBird's MobileDependency
    mobileDep := internal.MobileDependency{
        TunAdapter:    &tunAdapterWrapper{tunAdapter},
        IFaceDiscover: &ifaceDiscoverWrapper{ifaceDiscover},
        ...
    }
}
```

The Go-side wrapper structs (`tunAdapterWrapper`, `ifaceDiscoverWrapper`) implement NetBird's Go interfaces by delegating to the gomobile-bound Kotlin objects.

**Android SELinux:** Same `getifaddrs`-based interface enumeration as tsbridge. Register with `stdnet` or NetBird's equivalent interface discovery hook.

### 2. Kotlin: `NetBirdTunnel.kt`

```kotlin
class NetBirdTunnel(
    managementUrl: String,
    setupKey: String,
    hostname: String,
    stateDir: File,
) : Tunnel {
    private val native: NativeHandle = Nbbridge.startTunnel(
        managementUrl, setupKey, hostname, stateDir.absolutePath,
        AndroidTunAdapter(), AndroidIfaceDiscover()
    )
    // ... dial, listenUdp, socksAddress, listenTcp, localAddress, close
}
```

**AndroidTunAdapter (Kotlin):**
```kotlin
class AndroidTunAdapter : NativeTunAdapter {
    override fun configureInterface(address: String, addressV6: String,
        mtu: Int, dns: String, searchDomains: String, routes: String): Long {
        // Configure VpnService.Builder with addresses, DNS, routes
        // Return TUN file descriptor
    }
    override fun updateAddr(address: String) { /* update VpnService */ }
    override fun protectSocket(fd: Int): Boolean {
        // VpnService.protect(fd)
    }
}
```

### 3. Config Blob: `NetBirdConfigBlob.kt`

```kotlin
data class NetBirdConfigBlob(
    val managementUrl: String,
    val setupKey: String,
    val hostname: String = "",
) {
    fun encode(): ByteArray  // JSON
    companion object { fun parse(bytes: ByteArray): NetBirdConfigBlob }
}
```

JSON format:
```json
{
    "managementUrl": "https://netbird.example.com",
    "setupKey": "NB_SETUP_KEY_XXX",
    "hostname": "haven-phone"
}
```

### 4. Data Layer: `TunnelConfig.kt`

Add to `TunnelConfigType` enum:
```kotlin
enum class TunnelConfigType {
    WIREGUARD,
    TAILSCALE,
    CLOUDFLARE_ACCESS,
    NETBIRD,  // NEW
}
```

### 5. DI: `TunnelHiltModule.kt`

Add case to `DefaultTunnelFactory.create()`:
```kotlin
TunnelConfigType.NETBIRD -> {
    val parsed = NetBirdConfigBlob.parse(config.configText)
    NetBirdTunnel(
        managementUrl = parsed.managementUrl,
        setupKey = parsed.setupKey,
        hostname = parsed.hostname.ifBlank { deriveHostname(config.label) },
        stateDir = File(context.filesDir, "netbird-${config.id}"),
    )
}
```

### 6. UI: `TunnelViewModel.kt` + `TunnelsScreen.kt`

- `addNetBirdConfig(label, managementUrl, setupKey, hostname)` method
- New config type selector in UI with fields:
  - Management URL (text input, default: `https://api.netbird.io`)
  - Setup Key (text input, password-style)
  - Hostname (optional text input)

### 7. Go Dependencies: `go.mod`

Add:
```
github.com/netbirdio/netbird v0.71.4
```

Note: NetBird uses AGPLv3 for management/signal/relay directories and BSD-3 for client code. The bridge only uses client code.

## Error Handling

- **Auth failure**: Setup key invalid/expired → clear error message to user
- **Management unreachable**: Timeout after 30s → retry with backoff
- **Engine startup failure**: Return error from `StartTunnel`, no partial state
- **Dial after close**: Return "tunnel closed" error (same as wgbridge/tsbridge)
- **State persistence**: State dir preserved on Close for re-auth-free restart

## Testing

- `NetBirdConfigBlobTest.kt`: Parse valid JSON, legacy format fallback, invalid input
- Integration: Start tunnel with test NetBird server, verify dial works
- ConfigBlob tests follow same pattern as `TailscaleConfigBlobTest.kt`

## Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| NetBird Go module size (~50MB compiled) | Already have wireguard-go + tailscale; marginal increase |
| gomobile interface binding limitation | Use concrete wrapper struct with JNI callbacks |
| NetBird Engine complexity | Follow `RunOnAndroid` path which is designed for mobile embedding |
| AGPL licensing | Only client code (BSD-3) is used; management/signal/relay run server-side |
| Android SELinux restrictions | Same `getifaddrs` pattern as tsbridge |

## Files Summary

### New Files (4)
| File | Purpose |
|------|---------|
| `rclone-android/go/nbbridge/nbbridge.go` | Go bridge for NetBird ConnectClient |
| `core/tunnel/.../NetBirdTunnel.kt` | Kotlin Tunnel implementation |
| `core/tunnel/.../NetBirdConfigBlob.kt` | JSON config envelope |
| `core/tunnel/src/test/.../NetBirdConfigBlobTest.kt` | Config blob unit tests |

### Modified Files (7)
| File | Change |
|------|--------|
| `core/data/.../TunnelConfig.kt` | Add `NETBIRD` enum value |
| `core/tunnel/.../TunnelHiltModule.kt` | Add NetBird case to factory |
| `feature/tunnel/.../TunnelViewModel.kt` | Add `addNetBirdConfig()` |
| `feature/tunnel/.../TunnelsScreen.kt` | Add NetBird config UI |
| `rclone-android/go/go.mod` | Add netbird dependency |
| `rclone-android/go/go.sum` | Auto-generated |
| `settings.gradle.kts` | Update gomobile build comment |
