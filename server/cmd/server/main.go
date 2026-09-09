package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"zisee/server/internal/config"
	"zisee/server/internal/firestore"
	"zisee/server/internal/httpapi"
	"zisee/server/internal/identity"
	"zisee/server/internal/postgres"
	"zisee/server/internal/push"
	"zisee/server/internal/turn"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(logger); err != nil {
		// DSNs and database errors can include credentials. Never log raw errors.
		logger.Error("server_stopped_with_error")
		os.Exit(1)
	}
}

// openStore selects the backing store. Firestore wins when a project is set so
// a Cloud Run deployment needs no database URL at all.
func openStore(ctx context.Context, cfg config.Config) (identity.Store, func(), error) {
	if cfg.UsesFirestore() {
		store, err := firestore.Open(ctx, cfg.FirestoreProject, cfg.FirestoreDatabase)
		if err != nil {
			return nil, nil, err
		}
		return store, store.Close, nil
	}
	store, err := postgres.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return nil, nil, err
	}
	return store, store.Close, nil
}

func run(logger *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		logger.Error("configuration_invalid")
		return err
	}
	root, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	connectCtx, cancel := context.WithTimeout(root, 10*time.Second)
	store, closeStore, err := openStore(connectCtx, cfg)
	cancel()
	if err != nil {
		logger.Error("database_unavailable")
		return err
	}
	defer closeStore()
	api := httpapi.New(store, logger)
	if cfg.TurnConfigured() {
		api = api.WithTurn(turn.New(cfg.TurnKeyID, cfg.TurnAPIToken))
		logger.Info("turn_enabled")
	} else {
		logger.Info("turn_not_configured")
	}
	if cfg.PushConfigured() {
		registry, ok := store.(push.Registry)
		if !ok {
			logger.Error("push_registry_unavailable")
			return errors.New("push configured but store has no device registry")
		}
		sender, err := push.NewFCM(root, cfg.FCMProjectID)
		if err != nil {
			logger.Error("push_init_failed")
			return err
		}
		api = api.WithPush(push.NewGateway(sender, registry, logger))
		logger.Info("push_enabled")
	} else {
		logger.Info("push_not_configured")
	}
	server := &http.Server{
		Addr: cfg.Address, Handler: api.Handler(),
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 15 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 * 1024,
		BaseContext: func(net.Listener) context.Context { return root },
	}
	done := make(chan error, 1)
	go func() { done <- server.ListenAndServe() }()
	logger.Info("server_starting")
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case err := <-done:
			if errors.Is(err, http.ErrServerClosed) {
				return nil
			}
			return err
		case <-ticker.C:
			ctx, cancel := context.WithTimeout(root, 5*time.Second)
			if err := store.Cleanup(ctx, time.Now().UTC()); err != nil {
				logger.Error("expired_auth_cleanup_failed")
			}
			cancel()
		case <-root.Done():
			ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cancel()
			if err := server.Shutdown(ctx); err != nil {
				server.Close()
				return err
			}
			logger.Info("server_stopped")
			return nil
		}
	}
}
