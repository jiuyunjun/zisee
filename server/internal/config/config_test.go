package config

import "testing"

func TestConfig(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	t.Setenv("PORT", "8080")
	if _, err := Load(); err == nil {
		t.Fatal("must require persistent storage")
	}
	t.Setenv("DATABASE_URL", "postgres://localhost/example")
	for _, port := range []string{"0", "65536", "abc", "-1"} {
		t.Setenv("PORT", port)
		if _, err := Load(); err == nil {
			t.Fatalf("accepted port %s", port)
		}
	}
	t.Setenv("PORT", "")
	cfg, err := Load()
	if err != nil || cfg.Address != ":8080" {
		t.Fatal("default Cloud Run port")
	}
}

func TestTurnConfiguration(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://localhost/example")
	t.Setenv("PORT", "8080")
	cfg, err := Load()
	if err != nil || cfg.TurnConfigured() {
		t.Fatal("TURN must be optional")
	}
	t.Setenv("CLOUDFLARE_TURN_TOKEN_ID", "abc123")
	if _, err := Load(); err == nil {
		t.Fatal("key id without api token must be rejected")
	}
	t.Setenv("CLOUDFLARE_TURN_API_TOKEN", "token")
	cfg, err = Load()
	if err != nil || !cfg.TurnConfigured() {
		t.Fatal("both variables set must enable TURN")
	}
	t.Setenv("CLOUDFLARE_TURN_TOKEN_ID", "abc/../evil")
	if _, err := Load(); err == nil {
		t.Fatal("key id must not escape the request path")
	}
}
