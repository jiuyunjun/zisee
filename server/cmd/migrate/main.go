package main

import (
	"context"
	"log/slog"
	"os"
	"time"

	"zisee/server/internal/config"
	"zisee/server/internal/postgres"
)

func main() {
	if err := migrate(); err != nil {
		slog.Error("migration_failed")
		os.Exit(1)
	}
	slog.Info("migration_complete")
}

func migrate() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	store, err := postgres.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer store.Close()
	return store.Migrate(ctx)
}
