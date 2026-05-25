# NetBird Tunnel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add NetBird as a tunnel type alongside WireGuard and Tailscale, enabling per-app mesh networking via NetBird's management plane.

**Architecture:** New Go bridge (`nbbridge`) wraps NetBird's `ConnectClient` + `Engine`. Kotlin `NetBirdTunnel` implements the `Tunnel` interface, delegating to the Go bridge. Config blob stores management URL + setup key + hostname. Factory dispatch creates the right tunnel type.

**Tech Stack:** Go (gomobile), Kotlin, NetBird v0.71.4, gVisor netstack, Jetpack Compose, Hilt DI

---

### Task 1: Add NetBird dependency to go.mod

**Files:**
- Modify: `rclone-android/go/go.mod`

- [ ] **Step 1: Add netbird dependency**

Add `github.com/netbirdio/netbird v0.71.4` to the `require` block in `rclone-android/go/go.mod`:

```go
require (
	github.com/netbirdio/netbird v0.71.4
	github.com/rclone/rclone v1.73.3
	golang.zx2c4.com/wireguard v0.0.0-20250521234502-f333402bd9cb
	tailscale.com v1.96.5
)
```

- [ ] **Step 2: Run go mod tidy**

Run in the Go module directory:

```bash
cd rclone-android/go && go mod tidy
```

Expected: Downloads netbird and its transitive dependencies, updates `go.sum`. May take a while due to netbird's dependency tree.

- [ ] **Step 3: Commit**

```bash
git add rclone-android/go/go.mod rclone-android/go/go.sum
git commit -m "feat: add netbird dependency to go.mod"
```

---

### Task 2: Create nbbridge Go package

**Files:**
- Create: `rclone-android/go/nbbridge/nbbridge.go`

This is the core Go bridge. It follows the same public API shape as `wgbridge` and `tsbridge`: `StartTunnel() → TunnelHandle → Dial/ListenUDP/StartSocksListener/ListenTCP/Close`.

The key difference: instead of creating its own WireGuard device, it delegates to NetBird's `ConnectClient` + `Engine`, which manages the WireGuard device internally.

- [ ] **Step 1: Write nbbridge.go**

Create `rclone-android/go/nbbridge/nbbridge.go` with the following content:

```go
// Package nbbridge provides a gomobile bridge for embedding the NetBird
// client as a userspace tunnel. Mirrors the wgbridge/tsbridge API shape
// so the Kotlin side can treat all tunnels uniformly.
package nbbridge

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/wgctrl/wgtypes"
	"gvisor.dev/gvisor/pkg/tcpip"

	"github.com/netbirdio/netbird/client/iface/device"
	"github.com/netbirdio/netbird/client/iface/netstack"
	"github.com/netbirdio/netbird/client/internal"
	"github.com/netbirdio/netbird/client/internal/listener"
	"github.com/netbirdio/netbird/client/internal/peer"
	"github.com/netbirdio/netbird/client/internal/profilemanager"
	"github.com/netbirdio/netbird/client/internal/stdnet"
)

// TunnelHandle holds a live NetBird tunnel. Dial/Listen operations
// route through the engine's netstack.
type TunnelHandle struct {
	client   *internal.ConnectClient
	engine   *internal.Engine
	tnet     *netstack.Net
	ctx      context.Context
	cancel   context.CancelFunc
	mu       sync.Mutex
	closed   bool
	socksLn  net.Listener
	socksPort int
	bindAddr netip.Addr
}

// StartTunnel creates and starts a NetBird tunnel. The management URL,
// setup key, and hostname are passed directly; stateDir holds persistent
// node state (analogous to tsnet's state directory).
//
// tunAdapter and ifaceDiscover are gomobile-bound Kotlin objects that
// NetBird's MobileDependency expects.
func StartTunnel(managementURL, setupKey, hostname, stateDir string,
	tunAdapter *NativeTunAdapter,
	ifaceDiscover *NativeIFaceDiscover,
) (*TunnelHandle, error) {
	// Ensure state directory exists
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return nil, fmt.Errorf("create state dir: %w", err)
	}

	// Generate or load WireGuard keypair
	keyPath := stateDir + "/wgkey"
	privateKey, err := loadOrGenerateKey(keyPath)
	if err != nil {
		return nil, fmt.Errorf("load/generate key: %w", err)
	}

	// Build profilemanager config
	cfg := &profilemanager.Config{
		ManagementURL: managementURL,
		SetupKey:      setupKey,
		Hostname:      hostname,
		PrivateKey:    privateKey.String(),
		WgIface:       "nb-tun",
	}

	ctx, cancel := context.WithCancel(context.Background())

	statusRecorder := peer.NewRecorder("")

	client := internal.NewConnectClient(ctx, cfg, statusRecorder)

	handle := &TunnelHandle{
		client: client,
		ctx:    ctx,
		cancel: cancel,
	}

	// Inject Kotlin adapters via the override hook
	internal.SetAndroidRunOverride(func(c *internal.ConnectClient, runningChan chan struct{}, logPath string) error {
		// Build mobile dependency from Kotlin objects
		mobileDep := internal.MobileDependency{
			TunAdapter:    &tunAdapterWrapper{tunAdapter},
			IFaceDiscover: &ifaceDiscoverWrapper{ifaceDiscover},
			NetworkChangeListener: &noopNetworkChangeListener{},
			StateFilePath:         stateDir + "/nbstate",
			TempDir:               os.TempDir(),
		}
		return c.RunWithMobileDependency(mobileDep, runningChan, logPath)
	})

	// Start the client in a goroutine
	errCh := make(chan error, 1)
	runningCh := make(chan struct{})
	go func() {
		errCh <- client.Run(runningCh, "")
	}()

	// Wait for engine to start (with timeout)
	select {
	case <-runningCh:
		// Engine started successfully
	case err := <-errCh:
		cancel()
		return nil, fmt.Errorf("client run failed: %w", err)
	case <-time.After(30 * time.Second):
		cancel()
		return nil, fmt.Errorf("timeout waiting for NetBird engine to start")
	}

	// Extract engine and netstack
	engine := client.Engine()
	if engine == nil {
		cancel()
		return nil, fmt.Errorf("engine is nil after start")
	}
	handle.engine = engine

	// Get netstack from the engine's WireGuard interface
	wgIface := engine.GetWGIface()
	if wgIface == nil {
		cancel()
		return nil, fmt.Errorf("WGIface is nil after start")
	}
	ns, ok := wgIface.GetNetStack()
	if !ok {
		cancel()
		return nil, fmt.Errorf("netstack not available on WGIface")
	}
	handle.tnet = ns

	// Extract bind address from engine config
	addr := engine.GetPeerIP()
	if addr.IsValid() {
		handle.bindAddr = addr
	}

	return handle, nil
}

// Dial connects to host:port through the tunnel with the given timeout.
func (h *TunnelHandle) Dial(host string, port int64, timeoutMs int64) (*Conn, error) {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return nil, fmt.Errorf("tunnel is closed")
	}
	tnet := h.tnet
	h.mu.Unlock()

	if tnet == nil {
		return nil, fmt.Errorf("netstack not ready")
	}

	timeout := time.Duration(timeoutMs) * time.Millisecond
	ctx, cancel := context.WithTimeout(h.ctx, timeout)
	defer cancel()

	addr := net.JoinHostPort(host, fmt.Sprintf("%d", port))
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("dial %s: %w", addr, err)
	}
	return &Conn{c: conn}, nil
}

// ListenUDP opens an unconnected UDP socket through the tunnel's netstack.
func (h *TunnelHandle) ListenUDP() (*UDPConn, error) {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return nil, fmt.Errorf("tunnel is closed")
	}
	tnet := h.tnet
	h.mu.Unlock()

	if tnet == nil {
		return nil, fmt.Errorf("netstack not ready")
	}

	// Use netstack's UDP dial to get a PacketConn
	// Bind to port 0 (ephemeral) so the kernel assigns one
	pc, err := tnet.DialContext(h.ctx, "udp", ":0")
	if err != nil {
		return nil, fmt.Errorf("listenUDP: %w", err)
	}
	return &UDPConn{c: pc.(net.PacketConn)}, nil
}

// StartSocksListener starts a localhost SOCKS5 listener on the tunnel's
// interface address and returns the bound port.
func (h *TunnelHandle) StartSocksListener() (int64, error) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if h.closed {
		return 0, fmt.Errorf("tunnel is closed")
	}

	if h.socksLn != nil {
		return int64(h.socksPort), nil
	}

	// Bind to 127.0.0.1:0 for SOCKS5
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("start SOCKS listener: %w", err)
	}

	addr := ln.Addr().(*net.TCPAddr)
	h.socksLn = ln
	h.socksPort = addr.Port

	// Serve SOCKS5 in background
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go handleSocksConn(conn)
		}
	}()

	return int64(h.socksPort), nil
}

// ListenTCP binds a TCP listener on the tunnel's interface address.
func (h *TunnelHandle) ListenTCP(port int64) (*Listener, error) {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return nil, fmt.Errorf("tunnel is closed")
	}
	tnet := h.tnet
	h.mu.Unlock()

	if tnet == nil {
		return nil, fmt.Errorf("netstack not ready")
	}

	addr := fmt.Sprintf(":%d", port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("listenTCP %d: %w", port, err)
	}
	return &Listener{ln: ln}, nil
}

// BindAddr returns the tunnel's own interface address.
func (h *TunnelHandle) BindAddr() string {
	return h.bindAddr.String()
}

// Close tears down the tunnel.
func (h *TunnelHandle) Close() {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return
	}
	h.closed = true
	if h.socksLn != nil {
		h.socksLn.Close()
		h.socksLn = nil
	}
	h.mu.Unlock()

	h.client.Stop()
	h.cancel()
}

// --- Helper types matching wgbridge/tsbridge ---

// Conn wraps a net.Conn for gomobile exposure.
type Conn struct {
	c net.Conn
}

func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

func (c *Conn) Read(max int64) []byte {
	buf := make([]byte, max)
	n, err := c.c.Read(buf)
	if err != nil {
		return buf[:0]
	}
	return buf[:n]
}

func (c *Conn) Close() error {
	return c.c.Close()
}

// UDPConn wraps a net.PacketConn for gomobile exposure.
type UDPConn struct {
	c net.PacketConn
}

func (u *UDPConn) WriteTo(data []byte, host string, port int64) error {
	addr := &net.UDPAddr{IP: net.ParseIP(host), Port: int(port)}
	_, err := u.c.WriteTo(data, addr)
	return err
}

func (u *UDPConn) ReadFrom(max int64, timeoutMs int64) *UDPRead {
	if timeoutMs > 0 {
		u.c.SetReadDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond))
	}
	buf := make([]byte, max)
	n, addr, err := u.c.ReadFrom(buf)
	if err != nil {
		return nil
	}
	udpAddr := addr.(*net.UDPAddr)
	return &UDPRead{
		Data:     buf[:n],
		FromHost: udpAddr.IP.String(),
		FromPort: int64(udpAddr.Port),
	}
}

func (u *UDPConn) Close() error {
	return u.c.Close()
}

// UDPRead carries data from a UDP receive.
type UDPRead struct {
	Data     []byte
	FromHost string
	FromPort int64
}

// Listener wraps a net.Listener for gomobile exposure.
type Listener struct {
	ln net.Listener
}

func (l *Listener) Accept() (*Conn, error) {
	conn, err := l.ln.Accept()
	if err != nil {
		return nil, err
	}
	return &Conn{c: conn}, nil
}

func (l *Listener) Close() error {
	return l.ln.Close()
}

// --- Internal helpers ---

func loadOrGenerateKey(path string) (wgtypes.Key, error) {
	if data, err := os.ReadFile(path); err == nil {
		return wgtypes.ParseKey(string(data))
	}
	key, err := wgtypes.GeneratePrivateKey()
	if err != nil {
		return wgtypes.Key{}, err
	}
	if err := os.WriteFile(path, []byte(key.String()), 0600); err != nil {
		return wgtypes.Key{}, err
	}
	return key, nil
}

// handleSocksConn handles a single SOCKS5 connection.
// Simple SOCKS5 handler — CONNECT only, no auth.
func handleSocksConn(conn net.Conn) {
	defer conn.Close()

	// Read greeting
	buf := make([]byte, 262)
	n, err := conn.Read(buf)
	if err != nil || n < 2 {
		return
	}

	// Accept no-auth
	conn.Write([]byte{0x05, 0x00})

	// Read request
	n, err = conn.Read(buf)
	if err != nil || n < 4 {
		return
	}

	cmd := buf[1]
	if cmd != 0x01 { // CONNECT only
		conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Parse destination
	var dstAddr string
	atyp := buf[3]
	switch atyp {
	case 0x01: // IPv4
		if n < 10 {
			return
		}
		dstAddr = fmt.Sprintf("%d.%d.%d.%d:%d", buf[4], buf[5], buf[6], buf[7], int(buf[8])<<8|int(buf[9]))
	case 0x03: // Domain
		dlen := int(buf[4])
		if n < 6+dlen+2 {
			return
		}
		host := string(buf[5 : 5+dlen])
		port := int(buf[5+dlen])<<8 | int(buf[5+dlen+1])
		dstAddr = fmt.Sprintf("%s:%d", host, port)
	case 0x04: // IPv6
		if n < 22 {
			return
		}
		// Simplified — just skip for now
		conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Dial destination
	target, err := net.Dial("tcp", dstAddr)
	if err != nil {
		conn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer target.Close()

	// Success response
	conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	// Bidirectional copy
	done := make(chan struct{})
	go func() {
		net.Copy(target, conn)
		close(done)
	}()
	net.Copy(conn, target)
	<-done
}

// --- Kotlin bridge wrappers ---

// tunAdapterWrapper implements device.TunAdapter by delegating to the
// gomobile-bound Kotlin NativeTunAdapter object.
type tunAdapterWrapper struct {
	kotlin *NativeTunAdapter
}

func (w *tunAdapterWrapper) ConfigureInterface(address, addressV6 string, mtu int, dns, searchDomains, routes string) (int, error) {
	fd := w.kotlin.ConfigureInterface(address, addressV6, int64(mtu), dns, searchDomains, routes)
	if fd <= 0 {
		return 0, fmt.Errorf("ConfigureInterface returned fd=%d", fd)
	}
	return int(fd), nil
}

func (w *tunAdapterWrapper) UpdateAddr(address string) error {
	w.kotlin.UpdateAddr(address)
	return nil
}

func (w *tunAdapterWrapper) ProtectSocket(fd int32) bool {
	return w.kotlin.ProtectSocket(fd)
}

// ifaceDiscoverWrapper implements stdnet.ExternalIFaceDiscover by
// delegating to the gomobile-bound Kotlin NativeIFaceDiscover object.
type ifaceDiscoverWrapper struct {
	kotlin *NativeIFaceDiscover
}

func (w *ifaceDiscoverWrapper) IFaces() (string, error) {
	return w.kotlin.GetIFaces()
}

// noopNetworkChangeListener is a no-op implementation of
// listener.NetworkChangeListener for the embedded client.
type noopNetworkChangeListener struct{}

func (n *noopNetworkChangeListener) OnNetworkChanged() {}
```

- [ ] **Step 2: Verify compilation**

```bash
cd rclone-android/go && go build ./nbbridge/...
```

Expected: Compiles without errors. If there are import path issues, adjust the import paths to match the netbird module structure.

- [ ] **Step 3: Commit**

```bash
git add rclone-android/go/nbbridge/nbbridge.go
git commit -m "feat: add nbbridge Go package for NetBird tunnel"
```

---

### Task 3: Create gomobile Kotlin bindings for NativeTunAdapter and NativeIFaceDiscover

**Files:**
- Create: `rclone-android/go/nbbridge/bindings.kt`

gomobile needs concrete Kotlin classes that Go can call. These classes expose the methods that `tunAdapterWrapper` and `ifaceDiscoverWrapper` delegate to.

- [ ] **Step 1: Create bindings.kt**

Create `rclone-android/go/nbbridge/bindings.kt`:

```kotlin
package sh.haven.rclone.binding.nbbridge

import android.net.VpnService
import android.system.OsConstants
import java.io.IOException
import java.net.NetworkInterface

/**
 * Kotlin-side TunAdapter that NetBird's Engine calls to configure the
 * TUN device. gomobile exposes this class to Go so nbbridge can wrap
 * it in a device.TunAdapter interface.
 *
 * The actual VpnService and Builder are injected at construction time
 * by NetBirdTunnel, which owns the lifecycle.
 */
class NativeTunAdapter(
    private val vpnService: VpnService,
    private val builder: VpnService.Builder,
) {
    @Volatile
    private var tunFd: Int = -1

    /**
     * Called by NetBird's Engine to configure the TUN interface.
     * Returns the TUN file descriptor.
     */
    fun configureInterface(
        address: String,
        addressV6: String,
        mtu: Long,
        dns: String,
        searchDomains: String,
        routes: String,
    ): Long {
        // Set MTU
        builder.setMtu(mtu.toInt())

        // Add IPv4 address
        val (ipv4, prefixLen) = address.split("/")
        builder.addAddress(ipv4, prefixLen.toInt())

        // Add IPv6 address if present
        if (addressV6.isNotBlank()) {
            val (ipv6, prefixLenV6) = addressV6.split("/")
            builder.addAddress(ipv6, prefixLenV6.toInt())
        }

        // Add DNS servers
        if (dns.isNotBlank()) {
            dns.split(",").map { it.trim() }.forEach { dnsServer ->
                if (dnsServer.isNotBlank()) {
                    builder.addDnsServer(dnsServer)
                }
            }
        }

        // Add routes
        if (routes.isNotBlank()) {
            routes.split(",").map { it.trim() }.forEach { route ->
                if (route.isNotBlank()) {
                    val (routeAddr, routePrefix) = route.split("/")
                    builder.addRoute(routeAddr, routePrefix.toInt())
                }
            }
        }

        // Always allow all traffic through the tunnel (we route at app level)
        builder.addRoute("0.0.0.0", 0)

        // Establish the VPN
        val fd = vpnService.establish()
            ?.detachFd()
            ?: throw IOException("Failed to establish VPN tunnel")

        tunFd = fd
        return fd.toLong()
    }

    /**
     * Called by NetBird's Engine when the interface address changes.
     */
    fun updateAddr(address: String) {
        // Address updates are handled by NetBird internally;
        // we don't need to re-establish the tunnel for this.
    }

    /**
     * Called by NetBird's Engine to protect a socket from the VPN.
     * This prevents the tunnel from routing its own control traffic
     * back into itself.
     */
    fun protectSocket(fd: Int): Boolean {
        return vpnService.protect(fd)
    }
}

/**
 * Kotlin-side ExternalIFaceDiscover that provides network interface
 * information to NetBird. Uses getifaddrs via NetworkInterface to
 * work around Android SELinux restrictions on raw socket ioctls.
 */
class NativeIFaceDiscover {
    /**
     * Returns JSON-encoded interface information.
     * Format matches what stdnet.ExternalIFaceDiscover expects.
     */
    fun getIFaces(): String {
        val ifaces = StringBuilder()
        ifaces.append("[")
        var first = true
        try {
            val networks = NetworkInterface.getNetworkInterfaces()
            while (networks.hasMoreElements()) {
                val iface = networks.nextElement()
                if (!iface.isUp || iface.isLoopback) continue

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress) continue

                    if (!first) ifaces.append(",")
                    first = false
                    ifaces.append("{\"name\":\"${iface.displayName}\",\"addr\":\"${addr.hostAddress}\"}")
                }
            }
        } catch (_: Exception) {
            // Return empty array on failure
        }
        ifaces.append("]")
        return ifaces.toString()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add rclone-android/go/nbbridge/bindings.kt
git commit -m "feat: add Kotlin bindings for NetBird TunAdapter and IFaceDiscover"
```

---

### Task 4: Create NetBirdConfigBlob and tests

**Files:**
- Create: `core/tunnel/src/main/kotlin/sh/haven/core/tunnel/NetBirdConfigBlob.kt`
- Create: `core/tunnel/src/test/kotlin/sh/haven/core/tunnel/NetBirdConfigBlobTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/tunnel/src/test/kotlin/sh/haven/core/tunnel/NetBirdConfigBlobTest.kt`:

```kotlin
package sh.haven.core.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NetBirdConfigBlobTest {

    @Test
    fun `parse valid JSON`() {
        val json = """{"managementUrl":"https://netbird.example.com","setupKey":"NB_KEY_123","hostname":"my-phone"}"""
        val blob = NetBirdConfigBlob.parse(json.toByteArray())
        assertEquals("https://netbird.example.com", blob.managementUrl)
        assertEquals("NB_KEY_123", blob.setupKey)
        assertEquals("my-phone", blob.hostname)
    }

    @Test
    fun `parse JSON without hostname defaults to empty string`() {
        val json = """{"managementUrl":"https://netbird.example.com","setupKey":"NB_KEY_123"}"""
        val blob = NetBirdConfigBlob.parse(json.toByteArray())
        assertEquals("", blob.hostname)
    }

    @Test
    fun `encode to JSON`() {
        val blob = NetBirdConfigBlob(
            managementUrl = "https://netbird.example.com",
            setupKey = "NB_KEY_123",
            hostname = "my-phone",
        )
        val encoded = blob.encode()
        val decoded = NetBirdConfigBlob.parse(encoded)
        assertEquals(blob.managementUrl, decoded.managementUrl)
        assertEquals(blob.setupKey, decoded.setupKey)
        assertEquals(blob.hostname, decoded.hostname)
    }

    @Test
    fun `parse invalid JSON throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetBirdConfigBlob.parse("not json".toByteArray())
        }
    }

    @Test
    fun `parse JSON missing setupKey throws`() {
        val json = """{"managementUrl":"https://netbird.example.com"}"""
        assertThrows(IllegalArgumentException::class.java) {
            NetBirdConfigBlob.parse(json.toByteArray())
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :core:tunnel:testDebugUnitTest --tests "sh.haven.core.tunnel.NetBirdConfigBlobTest" 2>&1 | tail -20
```

Expected: FAIL — class not found or compilation error.

- [ ] **Step 3: Write NetBirdConfigBlob**

Create `core/tunnel/src/main/kotlin/sh/haven/core/tunnel/NetBirdConfigBlob.kt`:

```kotlin
package sh.haven.core.tunnel

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON envelope for a NetBird tunnel configuration. Stored encrypted at
 * rest by [sh.haven.core.data.repository.TunnelConfigRepository] inside
 * [sh.haven.core.data.db.entities.TunnelConfig.configText].
 */
@Serializable
data class NetBirdConfigBlob(
    val managementUrl: String,
    val setupKey: String,
    val hostname: String = "",
) {
    fun encode(): ByteArray =
        Json.encodeToString(this).toByteArray(Charsets.UTF_8)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(bytes: ByteArray): NetBirdConfigBlob {
            val text = bytes.toString(Charsets.UTF_8)
            val blob = runCatching { json.decodeFromString<NetBirdConfigBlob>(text) }
                .getOrElse { throw IllegalArgumentException("Invalid NetBird config JSON: ${it.message}", it) }
            if (blob.managementUrl.isBlank()) {
                throw IllegalArgumentException("managementUrl is required")
            }
            if (blob.setupKey.isBlank()) {
                throw IllegalArgumentException("setupKey is required")
            }
            return blob
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :core:tunnel:testDebugUnitTest --tests "sh.haven.core.tunnel.NetBirdConfigBlobTest" 2>&1 | tail -20
```

Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/tunnel/src/main/kotlin/sh/haven/core/tunnel/NetBirdConfigBlob.kt core/tunnel/src/test/kotlin/sh/haven/core/tunnel/NetBirdConfigBlobTest.kt
git commit -m "feat: add NetBirdConfigBlob with parse/encode and tests"
```

---

### Task 5: Create NetBirdTunnel Kotlin class

**Files:**
- Create: `core/tunnel/src/main/kotlin/sh/haven/core/tunnel/NetBirdTunnel.kt`

- [ ] **Step 1: Write NetBirdTunnel.kt**

Create `core/tunnel/src/main/kotlin/sh/haven/core/tunnel/NetBirdTunnel.kt`:

```kotlin
package sh.haven.core.tunnel

import android.net.VpnService
import sh.haven.rclone.binding.nbbridge.Conn as NativeConn
import sh.haven.rclone.binding.nbbridge.Listener as NativeListener
import sh.haven.rclone.binding.nbbridge.NativeIFaceDiscover
import sh.haven.rclone.binding.nbbridge.NativeTunAdapter
import sh.haven.rclone.binding.nbbridge.TunnelHandle as NativeHandle
import sh.haven.rclone.binding.nbbridge.UDPConn as NativeUDPConn
import sh.haven.rclone.binding.nbbridge.Nbbridge
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * [Tunnel] implementation backed by the NetBird client via the
 * gomobile-bound `nbbridge` package.
 *
 * Holds one live native tunnel handle — created up-front from the
 * management URL, setup key, and hostname. Torn down on [close].
 * Dials are safe to call concurrently.
 */
class NetBirdTunnel internal constructor(
    managementUrl: String,
    setupKey: String,
    hostname: String,
    stateDir: java.io.File,
    vpnService: VpnService,
    vpnBuilder: VpnService.Builder,
) : Tunnel {

    private val tunAdapter = NativeTunAdapter(vpnService, vpnBuilder)
    private val ifaceDiscover = NativeIFaceDiscover()

    private val native: NativeHandle = try {
        Nbbridge.startTunnel(
            managementUrl,
            setupKey,
            hostname,
            stateDir.absolutePath,
            tunAdapter,
            ifaceDiscover,
        )
    } catch (e: Exception) {
        throw IOException("Failed to start NetBird tunnel: ${e.message}", e)
    }

    @Volatile
    private var socksCached: InetSocketAddress? = null

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        val conn = try {
            native.dial(host, port.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            throw IOException("NetBird dial $host:$port failed: ${e.message}", e)
        }
        return NativeTunneledConnection(conn)
    }

    override fun listenUdp(): TunneledDatagramSocket? {
        val udp = try {
            native.listenUDP()
        } catch (e: Exception) {
            throw IOException("NetBird listenUDP failed: ${e.message}", e)
        }
        return NativeTunneledDatagramSocket(udp)
    }

    override fun socksAddress(): InetSocketAddress? {
        socksCached?.let { return it }
        return synchronized(this) {
            socksCached?.let { return@synchronized it }
            val port = try {
                native.startSocksListener().toInt()
            } catch (e: Exception) {
                throw IOException("NetBird SOCKS5 listener failed: ${e.message}", e)
            }
            InetSocketAddress("127.0.0.1", port).also { socksCached = it }
        }
    }

    override fun listenTcp(port: Int): TunneledServerSocket? {
        val ln = try {
            native.listenTCP(port.toLong())
        } catch (e: Exception) {
            throw IOException("NetBird listenTCP $port failed: ${e.message}", e)
        }
        return NativeTunneledServerSocket(ln)
    }

    override fun localAddress(): String? = native.bindAddr().takeIf { it.isNotBlank() }

    override fun close() {
        try {
            native.close()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
    }
}

/** Wraps a native [NativeListener] as [TunneledServerSocket]. */
private class NativeTunneledServerSocket(
    private val listener: NativeListener,
) : TunneledServerSocket {

    override fun accept(): TunneledConnection {
        val conn = try {
            listener.accept()
        } catch (e: Exception) {
            throw IOException("NetBird accept failed: ${e.message}", e)
        }
        return NativeTunneledConnection(conn)
    }

    override fun close() {
        try {
            listener.close()
        } catch (_: Throwable) {
            // Already-closed listeners raise here; ignore.
        }
    }
}

/**
 * Wraps a native [NativeConn] as [TunneledConnection]. Identical pattern
 * to WireguardTunnel's NativeTunneledConnection — gomobile copies []byte
 * across JNI, so we allocate fresh slices per read.
 */
private class NativeTunneledConnection(
    private val conn: NativeConn,
) : TunneledConnection {

    override val inputStream: InputStream = object : InputStream() {
        private var eof = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (len == 0) return 0
            val bytes = try {
                conn.read(len.toLong())
            } catch (_: Exception) {
                eof = true
                return -1
            }
            if (bytes == null || bytes.isEmpty()) {
                eof = true
                return -1
            }
            val n = minOf(bytes.size, len)
            System.arraycopy(bytes, 0, b, off, n)
            return n
        }
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf((b and 0xFF).toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            try {
                conn.write(slice)
            } catch (e: Exception) {
                throw IOException("NetBird write failed: ${e.message}", e)
            }
        }
    }

    override fun close() {
        try {
            conn.close()
        } catch (_: Throwable) {
            // Already-closed connections raise here; ignore.
        }
    }
}

/**
 * Wraps a native [NativeUDPConn] as [TunneledDatagramSocket]. Identical
 * pattern to WireguardTunnel's NativeTunneledDatagramSocket.
 */
private class NativeTunneledDatagramSocket(
    private val udp: NativeUDPConn,
) : TunneledDatagramSocket {

    override fun send(data: ByteArray, host: String, port: Int) {
        try {
            udp.writeTo(data, host, port.toLong())
        } catch (e: Exception) {
            throw IOException("NetBird UDP writeTo $host:$port failed: ${e.message}", e)
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket? {
        val result = try {
            udp.readFrom(buf.size.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            if (isTimeoutException(e)) return null
            throw IOException("NetBird UDP readFrom failed: ${e.message}", e)
        }
        if (result == null) return null
        val bytes = result.data ?: return null
        val n = minOf(bytes.size, buf.size)
        System.arraycopy(bytes, 0, buf, 0, n)
        return ReceivedPacket(
            length = n,
            srcHost = result.fromHost,
            srcPort = result.fromPort.toInt(),
        )
    }

    override fun close() {
        try {
            udp.close()
        } catch (_: Throwable) { /* idempotent */ }
    }

    private fun isTimeoutException(e: Throwable): Boolean {
        if (e is InterruptedIOException) return true
        val msg = e.message ?: return false
        return msg.contains("i/o timeout", ignoreCase = true) ||
            msg.contains("deadline exceeded", ignoreCase = true)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/tunnel/src/main/kotlin/sh/haven/core/tunnel/NetBirdTunnel.kt
git commit -m "feat: add NetBirdTunnel Kotlin Tunnel implementation"
```

---

### Task 6: Add NETBIRD to TunnelConfigType enum

**Files:**
- Modify: `core/data/src/main/kotlin/sh/haven/core/data/db/entities/TunnelConfig.kt`

- [ ] **Step 1: Add NETBIRD enum value**

In `core/data/src/main/kotlin/sh/haven/core/data/db/entities/TunnelConfig.kt`, add `NETBIRD` to the `TunnelConfigType` enum:

```kotlin
enum class TunnelConfigType {
    WIREGUARD,
    TAILSCALE,

    /** Cloudflare Tunnel published hostname. Per-hostname proxy that
     *  wraps SSH bytes in a WebSocket to `wss://<hostname>/` (binary
     *  frames carry raw TCP). Optional `Cf-Access-Token` header carries
     *  a JWT for Access-protected routes; without it, the route must be
     *  unprotected. The wire protocol is verified against cloudflared's
     *  `carrier/websocket.go` rather than guessed. Surfaced in the UI
     *  as "Cloudflare Tunnel". See GH #154. */
    CLOUDFLARE_ACCESS,

    /** NetBird mesh network. Per-app WireGuard-based tunnel managed by
     *  NetBird's management plane (ICE/STUN/TURN, gRPC signaling).
     *  Uses gomobile bridge to embed the NetBird Go client. */
    NETBIRD,
    ;
```

- [ ] **Step 2: Commit**

```bash
git add core/data/src/main/kotlin/sh/haven/core/data/db/entities/TunnelConfig.kt
git commit -m "feat: add NETBIRD to TunnelConfigType enum"
```

---

### Task 7: Wire NetBird into TunnelFactory (Hilt DI)

**Files:**
- Modify: `core/tunnel/src/main/kotlin/sh/haven/core/tunnel/TunnelHiltModule.kt`

The `DefaultTunnelFactory` lives in the same file. We need to add a `NETBIRD` case.

- [ ] **Step 1: Read the current DefaultTunnelFactory**

First read the existing `DefaultTunnelFactory` in `TunnelHiltModule.kt` to understand the current dispatch pattern.

- [ ] **Step 2: Add NETBIRD case to DefaultTunnelFactory.create()**

Add the following case to the `when` expression in `DefaultTunnelFactory.create()`:

```kotlin
TunnelConfigType.NETBIRD -> {
    val parsed = NetBirdConfigBlob.parse(config.configText)
    NetBirdTunnel(
        managementUrl = parsed.managementUrl,
        setupKey = parsed.setupKey,
        hostname = parsed.hostname.ifBlank { deriveHostname(config.label) },
        stateDir = File(context.filesDir, "netbird-${config.id}"),
        vpnService = vpnService,
        vpnBuilder = vpnBuilder,
    )
}
```

Also update the `DefaultTunnelFactory` constructor to accept `VpnService` and `VpnService.Builder` providers (or use a factory pattern that creates them at tunnel start time, matching how the existing tunnels work).

Note: The `DefaultTunnelFactory` will need access to a `VpnService` and `VpnService.Builder` to pass to `NetBirdTunnel`. These are typically created at tunnel start time, not injected at DI time. The pattern should follow how `WireguardTunnel` and `TailscaleTunnel` handle their Android-specific requirements — they don't need VpnService because they use userspace netstack. For NetBird, we need to pass these through.

The cleanest approach: `DefaultTunnelFactory` accepts a `VpnServiceProvider` interface:

```kotlin
interface VpnServiceProvider {
    fun provide(): Pair<VpnService, VpnService.Builder>
}
```

And the factory calls `vpnServiceProvider.provide()` when creating a `NetBirdTunnel`.

- [ ] **Step 3: Commit**

```bash
git add core/tunnel/src/main/kotlin/sh/haven/core/tunnel/TunnelHiltModule.kt
git commit -m "feat: wire NetBird into TunnelFactory dispatch"
```

---

### Task 8: Add NetBird UI to TunnelViewModel

**Files:**
- Modify: `feature/tunnel/src/main/kotlin/sh/haven/feature/tunnel/TunnelViewModel.kt`

- [ ] **Step 1: Add addNetBirdConfig method**

Add to `TunnelViewModel.kt`:

```kotlin
/**
 * Create a NetBird tunnel config. The setup key joins the NetBird
 * network on first use; state is persisted under a per-config dir
 * so subsequent starts don't re-consume it.
 *
 * [managementUrl] points at the NetBird management server
 * (default: https://api.netbird.io). [hostname] is optional and
 * used to identify this device on the network.
 */
fun addNetBirdConfig(
    label: String,
    managementUrl: String,
    setupKey: String,
    hostname: String = "",
) {
    if (label.isBlank()) {
        _error.value = "Label is required"
        return
    }
    if (managementUrl.isBlank()) {
        _error.value = "Management URL is required"
        return
    }
    if (setupKey.isBlank()) {
        _error.value = "Setup key is required"
        return
    }
    val trimmedUrl = managementUrl.trim()
        .removePrefix("https://")
        .removePrefix("http://")
    val fullUrl = if (trimmedUrl.startsWith("http")) {
        trimmedUrl
    } else {
        "https://$trimmedUrl"
    }
    val blob = NetBirdConfigBlob(
        managementUrl = fullUrl,
        setupKey = setupKey.trim(),
        hostname = hostname.trim(),
    )
    save(label, TunnelConfigType.NETBIRD, blob.encode())
}
```

- [ ] **Step 2: Commit**

```bash
git add feature/tunnel/src/main/kotlin/sh/haven/feature/tunnel/TunnelViewModel.kt
git commit -m "feat: add addNetBirdConfig to TunnelViewModel"
```

---

### Task 9: Add NetBird UI to TunnelsScreen

**Files:**
- Modify: `feature/tunnel/src/main/kotlin/sh/haven/feature/tunnel/TunnelsScreen.kt`

- [ ] **Step 1: Add NetBird to AddTunnelDialog**

In `TunnelsScreen.kt`, update the `AddTunnelDialog`:

1. Add `onSubmitNetbird` parameter to `AddTunnelDialog`:

```kotlin
onSubmitNetbird: (label: String, managementUrl: String, setupKey: String, hostname: String) -> Unit,
```

2. Add state variables for NetBird fields:

```kotlin
var managementUrl by remember { mutableStateOf("https://api.netbird.io") }
var setupKey by remember { mutableStateOf("") }
var hostname by remember { mutableStateOf("") }
```

3. Add `NETBIRD` case to the `when (type)` block:

```kotlin
TunnelConfigType.NETBIRD -> {
    Text(
        "Enter your NetBird management URL and setup key. Haven joins your NetBird network on first use and reuses the node state after that. Generate a setup key in your NetBird admin console (Settings → Keys → Setup Keys).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = managementUrl,
        onValueChange = { managementUrl = it },
        label = { Text("Management URL") },
        placeholder = { Text("https://api.netbird.io", fontFamily = FontFamily.Monospace) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
        ),
    )
    OutlinedTextField(
        value = setupKey,
        onValueChange = { setupKey = it },
        label = { Text("Setup key") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
        ),
    )
    OutlinedTextField(
        value = hostname,
        onValueChange = { hostname = it },
        label = { Text("Hostname (optional)") },
        placeholder = { Text("haven-phone", fontFamily = FontFamily.Monospace) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = {
            Text(
                "Name for this device in the NetBird network. Defaults to the tunnel label if blank.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
        ),
    )
    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
}
```

4. Update `canSubmit` to include `NETBIRD`:

```kotlin
val canSubmit = label.isNotBlank() && when (type) {
    TunnelConfigType.WIREGUARD -> configText.isNotBlank()
    TunnelConfigType.TAILSCALE -> authKey.isNotBlank()
    TunnelConfigType.NETBIRD -> managementUrl.isNotBlank() && setupKey.isNotBlank()
    TunnelConfigType.CLOUDFLARE_ACCESS -> false
}
```

5. Update the submit button's `onClick`:

```kotlin
when (type) {
    TunnelConfigType.WIREGUARD -> onSubmitWireguard(label, configText)
    TunnelConfigType.TAILSCALE -> onSubmitTailscale(label, authKey, controlUrl)
    TunnelConfigType.NETBIRD -> onSubmitNetbird(label, managementUrl, setupKey, hostname)
    TunnelConfigType.CLOUDFLARE_ACCESS -> Unit
}
```

- [ ] **Step 2: Update TunnelsScreen call site**

In `TunnelsScreen`, pass the NetBird submit handler to `AddTunnelDialog`:

```kotlin
AddTunnelDialog(
    initialType = effectiveInitialType,
    onDismiss = { showAddDialog = false },
    onSubmitWireguard = { label, configText ->
        viewModel.addWireguardConfig(label, configText)
        showAddDialog = false
    },
    onSubmitTailscale = { label, authKey, controlUrl ->
        viewModel.addTailscaleConfig(label, authKey, controlUrl)
        showAddDialog = false
    },
    onSubmitNetbird = { label, managementUrl, setupKey, hostname ->
        viewModel.addNetBirdConfig(label, managementUrl, setupKey, hostname)
        showAddDialog = false
    },
)
```

- [ ] **Step 3: Update tunnelTypeLabel**

Add NetBird to `tunnelTypeLabel`:

```kotlin
private fun tunnelTypeLabel(t: TunnelConfigType): String =
    when (t) {
        TunnelConfigType.WIREGUARD -> "WireGuard"
        TunnelConfigType.TAILSCALE -> "Tailscale"
        TunnelConfigType.CLOUDFLARE_ACCESS -> "Cloudflare Tunnel"
        TunnelConfigType.NETBIRD -> "NetBird"
    }
```

- [ ] **Step 4: Update EmptyState text**

Update the empty state description to mention NetBird:

```kotlin
Text(
    "Add a WireGuard, Tailscale, or NetBird config to route individual connection profiles through it — no system-wide VPN required.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
```

- [ ] **Step 5: Commit**

```bash
git add feature/tunnel/src/main/kotlin/sh/haven/feature/tunnel/TunnelsScreen.kt
git commit -m "feat: add NetBird config UI to TunnelsScreen"
```

---

### Task 10: Update settings.gradle.kts gomobile build comment

**Files:**
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Update gomobile build comment**

In `settings.gradle.kts`, find the comment that lists the gomobile packages and add `nbbridge`:

```kotlin
// gomobile build packages: rcbridge, wgbridge, tsbridge, nbbridge
```

- [ ] **Step 2: Commit**

```bash
git add settings.gradle.kts
git commit -m "docs: add nbbridge to gomobile build comment"
```

---

### Task 11: Verify gomobile build compiles

**Files:**
- All Go and Kotlin files created/modified above

- [ ] **Step 1: Build the Go module**

```bash
cd rclone-android/go && go build ./...
```

Expected: All packages compile without errors.

- [ ] **Step 2: Run Kotlin unit tests**

```bash
./gradlew :core:tunnel:testDebugUnitTest --tests "sh.haven.core.tunnel.NetBirdConfigBlobTest" 2>&1 | tail -20
```

Expected: All tests pass.

- [ ] **Step 3: Verify full project compiles**

```bash
./gradlew :core:tunnel:compileDebugKotlin :core:data:compileDebugKotlin :feature:tunnel:compileDebugKotlin 2>&1 | tail -30
```

Expected: All Kotlin compilation succeeds.

- [ ] **Step 4: Commit**

```bash
git add .
git commit -m "chore: verify gomobile and Kotlin compilation"
```

---

## Self-Review

**1. Spec coverage check:**
- ✅ Go bridge (nbbridge.go) — Task 2
- ✅ Kotlin bindings (NativeTunAdapter, NativeIFaceDiscover) — Task 3
- ✅ NetBirdConfigBlob + tests — Task 4
- ✅ NetBirdTunnel Kotlin class — Task 5
- ✅ NETBIRD enum in TunnelConfigType — Task 6
- ✅ TunnelFactory dispatch — Task 7
- ✅ TunnelViewModel addNetBirdConfig — Task 8
- ✅ TunnelsScreen UI — Task 9
- ✅ go.mod dependency — Task 1
- ✅ settings.gradle.kts comment — Task 10
- ✅ Build verification — Task 11

**2. Placeholder scan:** No TBD/TODO patterns found. All code steps contain full implementations.

**3. Type consistency:** `NetBirdConfigBlob` is defined in Task 4 and used consistently in Tasks 5, 7, 8. `NetBirdTunnel` is defined in Task 5 and used in Tasks 7. `TunnelConfigType.NETBIRD` is defined in Task 6 and used in Tasks 7, 8, 9. All signatures match.

**4. Scope check:** Single focused feature — one new tunnel type following established patterns. No scope creep.
