// Package nbbridge provides a minimal Go bridge over NetBird's embed client,
// exposed via gomobile for Haven's per-app NetBird support (#102 follow-up).
//
// Shares the public API shape with [wgbridge] and [tsbridge] — StartTunnel →
// TunnelHandle → Dial → Conn → Read/Write/Close — so the Kotlin side can
// treat all backends uniformly through [sh.haven.core.tunnel.Tunnel].
//
// Instead of creating its own WireGuard device, nbbridge delegates to
// NetBird's embed.Client, which manages the WireGuard device internally
// in userspace netstack mode.
package nbbridge

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/wgctrl/wgtypes"

	"github.com/netbirdio/netbird/client/embed"

	"sh.haven/rcbridge/socks5"
)

// TunnelHandle holds a live NetBird tunnel. Safe to Dial concurrently;
// Close is idempotent but not safe to race with Dial.
type TunnelHandle struct {
	client    *embed.Client
	mu        sync.Mutex
	closed    bool
	socksLn   net.Listener
	socksPort int
	bindAddr  netip.Addr
}

// Conn is a TCP connection through a [TunnelHandle]. Bound to gomobile;
// Read returns a fresh byte slice because gomobile passes []byte by copy.
type Conn struct {
	c net.Conn
}

// UDPConn is an unconnected UDP socket inside the tunnel's netstack.
// Mirrors wgbridge.UDPConn so the Kotlin TunneledDatagramSocket
// implementation can treat both backends identically.
type UDPConn struct {
	c net.PacketConn
}

// UDPRead packages a single ReadFrom result for gomobile.
// See wgbridge.UDPRead.
type UDPRead struct {
	Data     []byte
	FromHost string
	FromPort int
}

// Listener accepts inbound TCP connections on the tunnel's WireGuard
// interface address inside the netstack.
type Listener struct {
	ln net.Listener
}

// NativeTunAdapter is a gomobile-bound Kotlin class. Kept in the
// StartTunnel signature for API parity; in netstack mode the TUN is
// created by the userspace stack so this is not used directly.
type NativeTunAdapter struct {
	handle int64
}

// NativeIFaceDiscover is a gomobile-bound Kotlin class that provides
// network interface enumeration via Android's ConnectivityManager.
// In the current embed mode, interface discovery falls back to the
// built-in no-op (relay-only ICE); this parameter is kept for future
// use when gomobile bindings expose the enumeration callback.
type NativeIFaceDiscover struct {
	handle int64
}

// StartTunnel brings up a NetBird tailnet using the given management
// server URL, setup key (for initial network join), and hostname
// (advertised to the tailnet). stateDir is used for persisted client
// state (keys, peer cache) and is created if missing.
//
// tunAdapter and ifaceDiscover are gomobile-bound Kotlin objects kept
// for API parity with the task spec. In userspace netstack mode the
// embed client creates its own TUN and uses a no-op interface
// discoverer (connections fall back to NetBird relay servers for ICE).
//
// Blocks until the engine has started and the netstack is ready.
// Internal timeout is 60 s — first-run setup key consumption + control-
// plane handshake + peer-map sync can add up on a phone with flaky NAT.
//
// Returns errors carrying the underlying NetBird diagnostic; common
// causes include expired/invalid setup key, management server
// unreachable, or WireGuard key generation failure.
func StartTunnel(managementURL, setupKey, hostname, stateDir string,
	tunAdapter *NativeTunAdapter,
	ifaceDiscover *NativeIFaceDiscover,
) (*TunnelHandle, error) {
	if managementURL == "" {
		return nil, errors.New("management URL required")
	}
	if stateDir == "" {
		return nil, errors.New("state directory required")
	}
	if hostname == "" {
		hostname = "haven-android"
	}
	_ = tunAdapter   // not used in netstack mode
	_ = ifaceDiscover // not used in netstack mode (relay-only ICE)

	// Create state directory.
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return nil, fmt.Errorf("create state directory: %w", err)
	}

	// Ensure a WireGuard private key exists in stateDir. The embed
	// client can generate one, but we pre-generate here so the key is
	// available for inspection/debugging and matches the wgbridge/
	// tsbridge pattern of persisting wgkey.
	privateKey, err := loadOrGenerateKey(stateDir)
	if err != nil {
		return nil, fmt.Errorf("load/generate WireGuard key: %w", err)
	}

	// Enable userspace netstack mode via environment variables.
	// The embed client checks these env vars in New() to configure the
	// networking stack. There are no per-client Options fields for these
	// settings, so we must set them process-wide. This is a known
	// limitation: concurrent nbbridge and non-nbbridge clients in the
	// same process would share these settings.
	os.Setenv("NB_USE_NETSTACK_MODE", "true")
	os.Setenv("NB_NETSTACK_SKIP_PROXY", "true")

	// Disable server routes — we only need the overlay for tunneling,
	// not to become a routing peer for the tailnet.
	disableRoutes := true
	wgPort := 51820

	// Build embed client options.
	opts := embed.Options{
		DeviceName:          hostname,
		SetupKey:            setupKey,
		ManagementURL:       managementURL,
		PrivateKey:          privateKey,
		ConfigPath:          filepath.Join(stateDir, "config.json"),
		StatePath:           filepath.Join(stateDir, "state.json"),
		DisableClientRoutes: disableRoutes,
		BlockInbound:        true, // we're a client, not a server
		WireguardPort:       &wgPort,
	}

	client, err := embed.New(opts)
	if err != nil {
		return nil, fmt.Errorf("create NetBird embed client: %w", err)
	}

	// Start the client with a timeout. The embed client blocks until
	// the engine is started (login accepted + peer map received).
	startCtx, startCancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer startCancel()

	if err := client.Start(startCtx); err != nil {
		return nil, fmt.Errorf("start NetBird client: %w", err)
	}

	// Extract the overlay address from client status.
	bindAddr, err := getBindAddr(client)
	if err != nil {
		_ = client.Stop(context.Background())
		return nil, fmt.Errorf("get bind address: %w", err)
	}

	return &TunnelHandle{
		client:   client,
		bindAddr: bindAddr,
	}, nil
}

// getBindAddr extracts the overlay IP from the running client's status.
// Retries a few times because the local peer IP may not be immediately
// available after client.Start returns.
func getBindAddr(client *embed.Client) (netip.Addr, error) {
	const maxRetries = 3
	var lastErr error
	for i := 0; i < maxRetries; i++ {
		if i > 0 {
			time.Sleep(1 * time.Second)
		}
		status, err := client.Status()
		if err != nil {
			lastErr = fmt.Errorf("get client status: %w", err)
			continue
		}

		ipStr := status.LocalPeerState.IP
		if ipStr == "" {
			lastErr = errors.New("no local peer IP assigned yet")
			continue
		}

		addr, err := netip.ParseAddr(ipStr)
		if err != nil {
			lastErr = fmt.Errorf("parse local peer IP %q: %w", ipStr, err)
			continue
		}

		return addr, nil
	}
	return netip.Addr{}, fmt.Errorf("get bind addr after %d retries: %w", maxRetries, lastErr)
}

// loadOrGenerateKey loads a WireGuard private key from stateDir/wgkey,
// or generates and persists a new one if the file doesn't exist.
func loadOrGenerateKey(stateDir string) (string, error) {
	keyPath := filepath.Join(stateDir, "wgkey")

	// Try to load existing key.
	if data, err := os.ReadFile(keyPath); err == nil {
		key := string(data)
		if _, err := wgtypes.ParseKey(key); err == nil {
			return key, nil
		}
		// Invalid key file — generate a new one.
	}

	// Generate new key.
	wgKey, err := wgtypes.GeneratePrivateKey()
	if err != nil {
		return "", fmt.Errorf("generate WireGuard key: %w", err)
	}
	key := wgKey.String()

	// Persist key with restrictive permissions.
	if err := os.WriteFile(keyPath, []byte(key), 0600); err != nil {
		return "", fmt.Errorf("persist WireGuard key: %w", err)
	}

	return key, nil
}

// Dial opens a TCP connection through the tunnel. host may be a
// tailnet IP, MagicDNS name, or any IP reachable through the NetBird
// overlay. timeoutMs <= 0 means 30 s.
func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (*Conn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	client := t.client
	t.mu.Unlock()

	if timeoutMs <= 0 {
		timeoutMs = 30_000
	}
	ctx, cancel := context.WithTimeout(
		context.Background(),
		time.Duration(timeoutMs)*time.Millisecond,
	)
	defer cancel()

	c, err := client.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d via NetBird: %w", host, port, err)
	}
	return &Conn{c: c}, nil
}

// ListenUDP opens an unconnected UDP socket inside the tunnel's
// netstack, bound to the tunnel's own WireGuard interface address on
// an ephemeral port. Packets sent through this socket traverse the
// NetBird overlay rather than the device's default route.
//
// Returns an error if the tunnel is closed or has no usable interface
// address (shouldn't happen post-[StartTunnel]).
func (t *TunnelHandle) ListenUDP() (*UDPConn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	client := t.client
	bindAddr := t.bindAddr
	t.mu.Unlock()

	if !bindAddr.IsValid() {
		return nil, errors.New("NetBird has not assigned a usable IP yet")
	}

	// Use the embed client's ListenUDP which binds to the overlay IP.
	// Port 0 means ephemeral.
	addr := net.JoinHostPort(bindAddr.String(), "0")
	pc, err := client.ListenUDP(addr)
	if err != nil {
		return nil, fmt.Errorf("ListenUDP %s: %w", addr, err)
	}
	return &UDPConn{c: pc}, nil
}

// StartSocksListener lazily binds a 127.0.0.1 SOCKS5 listener fronting
// this tunnel and returns its bound TCP port. Idempotent; closing the
// tunnel tears the listener down. Mirrors wgbridge/tsbridge equivalent.
func (t *TunnelHandle) StartSocksListener() (int, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return 0, errors.New("tunnel closed")
	}
	if t.socksLn != nil {
		port := t.socksPort
		t.mu.Unlock()
		return port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.mu.Unlock()
		return 0, fmt.Errorf("bind SOCKS5 listener: %w", err)
	}
	t.socksLn = ln
	t.socksPort = ln.Addr().(*net.TCPAddr).Port
	client := t.client
	t.mu.Unlock()

	go socks5.Serve(ln, func(host string, port int) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return client.Dial(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	})

	return t.socksPort, nil
}

// ListenTCP binds a TCP listener on the tunnel's WireGuard interface
// address inside the netstack. Lets an on-device server accept
// connections from NetBird peers.
func (t *TunnelHandle) ListenTCP(port int) (*Listener, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	client := t.client
	bindAddr := t.bindAddr
	t.mu.Unlock()

	if !bindAddr.IsValid() {
		return nil, errors.New("NetBird has not assigned a usable IP yet")
	}

	// Use the embed client's ListenTCP which binds to the overlay IP.
	addr := net.JoinHostPort(bindAddr.String(), strconv.Itoa(port))
	ln, err := client.ListenTCP(addr)
	if err != nil {
		return nil, fmt.Errorf("ListenTCP %s: %w", addr, err)
	}
	return &Listener{ln: ln}, nil
}

// BindAddr returns the tunnel's WireGuard interface IP as the listen
// address for [ListenTCP] and the host a peer dials to reach a server
// bound on it. Empty if unset.
func (t *TunnelHandle) BindAddr() string {
	if t.bindAddr.IsValid() {
		return t.bindAddr.String()
	}
	return ""
}

// Close tears down the NetBird tunnel. The state directory is kept
// intact so a subsequent StartTunnel picks up without re-auth.
// Idempotent.
func (t *TunnelHandle) Close() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return
	}
	t.closed = true

	if t.socksLn != nil {
		t.socksLn.Close()
		t.socksLn = nil
	}
	if t.client != nil {
		_ = t.client.Stop(context.Background())
		t.client = nil
	}
}

// Accept blocks until a peer connects, returning the connection wrapped
// as a [Conn]. A non-nil error means the listener (or tunnel) was closed.
func (l *Listener) Accept() (*Conn, error) {
	c, err := l.ln.Accept()
	if err != nil {
		return nil, err
	}
	return &Conn{c: c}, nil
}

// Addr returns the bound "ip:port" for diagnostics.
func (l *Listener) Addr() string {
	return l.ln.Addr().String()
}

// Close stops accepting. Idempotent.
func (l *Listener) Close() error {
	return l.ln.Close()
}

// Read returns up to size bytes from the connection. A nil slice with a
// non-nil error signals EOF or a transport failure.
func (c *Conn) Read(size int) ([]byte, error) {
	if size <= 0 {
		size = 4096
	}
	buf := make([]byte, size)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], err
	}
	if err == nil {
		return nil, io.EOF
	}
	return nil, err
}

// Write writes all of data. gomobile copies the slice across the JNI
// boundary, so we don't need to worry about the caller mutating the
// underlying array before we're done.
func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

// Close closes the connection. Idempotent.
func (c *Conn) Close() error {
	return c.c.Close()
}

// ReadFrom blocks until a datagram arrives or the deadline expires.
// Same timeout semantics as wgbridge.UDPConn.ReadFrom.
func (u *UDPConn) ReadFrom(size int, timeoutMs int) (*UDPRead, error) {
	if size <= 0 {
		size = 2048
	}
	if timeoutMs > 0 {
		_ = u.c.SetReadDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond))
	} else {
		_ = u.c.SetReadDeadline(time.Time{})
	}
	buf := make([]byte, size)
	n, addr, err := u.c.ReadFrom(buf)
	if err != nil {
		return nil, err
	}
	udp, ok := addr.(*net.UDPAddr)
	if !ok {
		return &UDPRead{Data: buf[:n], FromHost: addr.String(), FromPort: 0}, nil
	}
	return &UDPRead{Data: buf[:n], FromHost: udp.IP.String(), FromPort: udp.Port}, nil
}

// WriteTo sends data to host:port through the tunnel. host must be a
// literal IP (MagicDNS resolution belongs in the bootstrap path, not
// the per-packet UDP send path).
func (u *UDPConn) WriteTo(data []byte, host string, port int) error {
	ip := net.ParseIP(host)
	if ip == nil {
		return fmt.Errorf("WriteTo: host %q is not an IP literal", host)
	}
	addr := &net.UDPAddr{IP: ip, Port: port}
	_, err := u.c.WriteTo(data, addr)
	return err
}

// Close closes the UDP socket. Idempotent.
func (u *UDPConn) Close() error {
	return u.c.Close()
}
