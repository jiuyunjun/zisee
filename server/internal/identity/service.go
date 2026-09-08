package identity

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

var identityID = regexp.MustCompile(`^zid_[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
var deviceID = regexp.MustCompile(`^zdev_[A-Za-z0-9_-]{22,64}$`)
var encoding = base64.RawURLEncoding

type Service struct {
	store Store
	now   func() time.Time
}

func New(store Store) *Service { return &Service{store: store, now: time.Now} }

func NormalizeName(name string) (string, error) {
	if !utf8.ValidString(name) {
		return "", ErrInvalid
	}
	name = strings.TrimSpace(name)
	if n := utf8.RuneCountInString(name); n < 1 || n > 40 {
		return "", ErrInvalid
	}
	for _, r := range name {
		if unicode.IsControl(r) {
			return "", ErrInvalid
		}
	}
	return name, nil
}

// BootstrapPayload is a byte-exact protocol, not JSON canonicalization.
func BootstrapPayload(r Registration) []byte {
	return []byte("zisee.bootstrap.v1\n" + r.IdentityID + "\n" + r.DeviceID + "\n" + r.PublicKey + "\n" + encoding.EncodeToString([]byte(r.DisplayName)))
}

func ChallengePayload(c Challenge) []byte {
	return []byte("zisee.auth.v1\n" + c.ID + "\n" + c.Nonce)
}

func parseKey(der []byte) (*ecdsa.PublicKey, error) {
	key, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, ErrInvalid
	}
	ec, ok := key.(*ecdsa.PublicKey)
	if !ok || ec.Curve != elliptic.P256() {
		return nil, ErrInvalid
	}
	return ec, nil
}

func verify(key *ecdsa.PublicKey, payload []byte, signature string) bool {
	sig, err := encoding.DecodeString(signature)
	if err != nil || len(sig) > 80 {
		return false
	}
	digest := sha256.Sum256(payload)
	return ecdsa.VerifyASN1(key, digest[:], sig)
}

func (s *Service) Bootstrap(ctx context.Context, req Registration) (Identity, error) {
	name, err := NormalizeName(req.DisplayName)
	if err != nil || name != req.DisplayName || !identityID.MatchString(req.IdentityID) || !deviceID.MatchString(req.DeviceID) {
		return Identity{}, ErrInvalid
	}
	der, err := encoding.DecodeString(req.PublicKey)
	if err != nil || len(der) > 256 || encoding.EncodeToString(der) != req.PublicKey {
		return Identity{}, ErrInvalid
	}
	key, err := parseKey(der)
	if err != nil {
		return Identity{}, err
	}
	if !verify(key, BootstrapPayload(req), req.Signature) {
		return Identity{}, ErrUnauthorized
	}
	now := s.now().UTC()
	return s.store.Register(ctx,
		Identity{ID: req.IdentityID, DisplayName: name, CreatedAt: now, UpdatedAt: now},
		Device{ID: req.DeviceID, IdentityID: req.IdentityID, PublicKey: der})
}

func randomValue() string {
	value := make([]byte, 32)
	rand.Read(value)
	return encoding.EncodeToString(value)
}

func (s *Service) NewChallenge(ctx context.Context, id string) (Challenge, error) {
	if !deviceID.MatchString(id) {
		return Challenge{}, ErrInvalid
	}
	if _, err := s.store.Device(ctx, id); err != nil {
		return Challenge{}, err
	}
	now := s.now().UTC()
	challenge := Challenge{ID: randomValue(), Nonce: randomValue(), DeviceID: id, ExpiresAt: now.Add(ChallengeTTL)}
	return challenge, s.store.PutChallenge(ctx, challenge, now)
}

func (s *Service) Authenticate(ctx context.Context, id, signature string) (Token, error) {
	if len(id) != 43 || len(signature) > 110 {
		return Token{}, ErrInvalid
	}
	now := s.now().UTC()
	challenge, err := s.store.Challenge(ctx, id, now)
	if err != nil {
		return Token{}, err
	}
	device, err := s.store.Device(ctx, challenge.DeviceID)
	if err != nil {
		return Token{}, err
	}
	key, err := parseKey(device.PublicKey)
	if err != nil || !verify(key, ChallengePayload(challenge), signature) {
		return Token{}, ErrUnauthorized
	}
	token := Token{AccessToken: randomValue(), TokenType: "Bearer", ExpiresAt: now.Add(SessionTTL)}
	hash := sha256.Sum256([]byte(token.AccessToken))
	session := Session{IdentityID: device.IdentityID, DeviceID: device.ID, TokenHash: hash[:], ExpiresAt: token.ExpiresAt}
	if err := s.store.Redeem(ctx, challenge, session, now); err != nil {
		return Token{}, err
	}
	return token, nil
}

func (s *Service) Authorize(ctx context.Context, token string) (Session, error) {
	decoded, err := encoding.DecodeString(token)
	if err != nil || len(decoded) != 32 || encoding.EncodeToString(decoded) != token {
		return Session{}, ErrUnauthorized
	}
	hash := sha256.Sum256([]byte(token))
	return s.store.Session(ctx, hash[:], s.now().UTC())
}

func (s *Service) Me(ctx context.Context, session Session) (Identity, error) {
	return s.store.Identity(ctx, session.IdentityID)
}

func (s *Service) Rename(ctx context.Context, session Session, name string) (Identity, error) {
	name, err := NormalizeName(name)
	if err != nil {
		return Identity{}, err
	}
	return s.store.Rename(ctx, session.IdentityID, name, s.now().UTC())
}

func (s *Service) Logout(ctx context.Context, session Session) error {
	return s.store.DeleteSession(ctx, session.TokenHash)
}
