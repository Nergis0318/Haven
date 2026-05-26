package nbbridge

import (
	"os"
	"path/filepath"
	"testing"
)

func TestStartTunnelMissingManagementURL(t *testing.T) {
	_, err := StartTunnel("", "key", "host", t.TempDir(), nil, nil)
	if err == nil {
		t.Fatal("expected error for empty management URL")
	}
	if got := err.Error(); got != "management URL required" {
		t.Fatalf("expected 'management URL required', got %q", got)
	}
}

func TestStartTunnelMissingStateDir(t *testing.T) {
	_, err := StartTunnel("https://api.netbird.io:443", "key", "host", "", nil, nil)
	if err == nil {
		t.Fatal("expected error for empty state directory")
	}
	if got := err.Error(); got != "state directory required" {
		t.Fatalf("expected 'state directory required', got %q", got)
	}
}

func TestStartTunnelInvalidManagementURL(t *testing.T) {
	_, err := StartTunnel("://not-a-url", "key", "host", t.TempDir(), nil, nil)
	if err == nil {
		t.Fatal("expected error for invalid management URL")
	}
}

func TestLoadOrGenerateKey(t *testing.T) {
	dir := t.TempDir()
	keyPath := filepath.Join(dir, "wgkey")

	// First call should generate a new key.
	key1, err := loadOrGenerateKey(dir)
	if err != nil {
		t.Fatalf("loadOrGenerateKey: %v", err)
	}
	if key1 == "" {
		t.Fatal("expected non-empty key")
	}

	// Key file should exist.
	if _, err := os.Stat(keyPath); err != nil {
		t.Fatalf("key file should exist: %v", err)
	}

	// Second call should return the same key.
	key2, err := loadOrGenerateKey(dir)
	if err != nil {
		t.Fatalf("loadOrGenerateKey (second): %v", err)
	}
	if key1 != key2 {
		t.Fatal("expected same key on second call")
	}
}

func TestLoadOrGenerateKeyInvalidFile(t *testing.T) {
	dir := t.TempDir()
	keyPath := filepath.Join(dir, "wgkey")

	// Write an invalid key.
	if err := os.WriteFile(keyPath, []byte("not-a-valid-key"), 0600); err != nil {
		t.Fatal(err)
	}

	// Should generate a new key (overwriting the invalid one).
	key, err := loadOrGenerateKey(dir)
	if err != nil {
		t.Fatalf("loadOrGenerateKey: %v", err)
	}
	if key == "not-a-valid-key" {
		t.Fatal("expected new key, not the invalid one")
	}
}

func TestTunnelHandleClosedErrors(t *testing.T) {
	h := &TunnelHandle{closed: true}

	_, err := h.Dial("10.0.0.1", 80, 1000)
	if err == nil || err.Error() != "tunnel closed" {
		t.Fatalf("Dial: expected 'tunnel closed', got %v", err)
	}

	_, err = h.ListenUDP()
	if err == nil || err.Error() != "tunnel closed" {
		t.Fatalf("ListenUDP: expected 'tunnel closed', got %v", err)
	}

	_, err = h.ListenTCP(8080)
	if err == nil || err.Error() != "tunnel closed" {
		t.Fatalf("ListenTCP: expected 'tunnel closed', got %v", err)
	}

	_, err = h.StartSocksListener()
	if err == nil || err.Error() != "tunnel closed" {
		t.Fatalf("StartSocksListener: expected 'tunnel closed', got %v", err)
	}

	// Close should be idempotent.
	h.Close()
	h.Close() // should not panic
}

func TestTunnelHandleCloseIdempotent(t *testing.T) {
	h := &TunnelHandle{}
	h.Close()
	h.Close() // should not panic
}

func TestBindAddrEmpty(t *testing.T) {
	h := &TunnelHandle{}
	if got := h.BindAddr(); got != "" {
		t.Fatalf("expected empty bind addr, got %q", got)
	}
}

func TestListenerClose(t *testing.T) {
	// Can't test Accept without a real tunnel, but Close should work.
	h := &TunnelHandle{closed: true}
	_, err := h.ListenTCP(0)
	if err == nil || err.Error() != "tunnel closed" {
		t.Fatalf("ListenTCP on closed: expected 'tunnel closed', got %v", err)
	}
}
