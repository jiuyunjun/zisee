package config

import (
	"errors"
	"net"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	Address, DatabaseURL string
	// Firestore is selected by project id; Postgres remains for local testing.
	FirestoreProject, FirestoreDatabase string
	// Empty when no TURN key is provisioned: the API then serves STUN only and
	// calls fall back to direct connections instead of failing to start.
	TurnKeyID, TurnAPIToken string
}

func (c Config) TurnConfigured() bool { return c.TurnKeyID != "" && c.TurnAPIToken != "" }
func (c Config) UsesFirestore() bool  { return c.FirestoreProject != "" }

func Load() (Config, error) {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	value, err := strconv.Atoi(port)
	if err != nil || value < 1 || value > 65535 {
		return Config{}, errors.New("PORT must be in 1..65535")
	}
	project := strings.TrimSpace(os.Getenv("FIRESTORE_PROJECT_ID"))
	database := os.Getenv("DATABASE_URL")
	if project == "" && database == "" {
		return Config{}, errors.New("FIRESTORE_PROJECT_ID or DATABASE_URL is required; no in-memory fallback")
	}
	keyID := strings.TrimSpace(os.Getenv("CLOUDFLARE_TURN_TOKEN_ID"))
	token := strings.TrimSpace(os.Getenv("CLOUDFLARE_TURN_API_TOKEN"))
	// The key id is a path segment; reject anything that could escape it.
	if strings.ContainsAny(keyID, "/?#%") {
		return Config{}, errors.New("CLOUDFLARE_TURN_TOKEN_ID must be a plain identifier")
	}
	if (keyID == "") != (token == "") {
		return Config{}, errors.New("CLOUDFLARE_TURN_TOKEN_ID and CLOUDFLARE_TURN_API_TOKEN must be set together")
	}
	return Config{
		Address: net.JoinHostPort("", port), DatabaseURL: database,
		FirestoreProject: project, FirestoreDatabase: strings.TrimSpace(os.Getenv("FIRESTORE_DATABASE_ID")),
		TurnKeyID: keyID, TurnAPIToken: token,
	}, nil
}
