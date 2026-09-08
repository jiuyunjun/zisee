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
