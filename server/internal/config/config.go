package config

import (
	"errors"
	"net"
	"os"
	"strconv"
)

type Config struct{ Address, DatabaseURL string }

func Load() (Config, error) {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	value, err := strconv.Atoi(port)
	if err != nil || value < 1 || value > 65535 {
		return Config{}, errors.New("PORT must be in 1..65535")
	}
	database := os.Getenv("DATABASE_URL")
	if database == "" {
		return Config{}, errors.New("DATABASE_URL is required; no in-memory fallback")
	}
	return Config{Address: net.JoinHostPort("", port), DatabaseURL: database}, nil
}
