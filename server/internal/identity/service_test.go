package identity

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"testing"
)

func TestNames(t *testing.T) {
	for _, invalid := range []string{"", "  ", "a\nb", string([]byte{0xff}), string(make([]byte, 41))} {
		if _, err := NormalizeName(invalid); err == nil {
			t.Errorf("accepted invalid name")
		}
	}
	value, err := NormalizeName("  九云😀  ")
	if err != nil || value != "九云😀" {
		t.Fatal("Unicode normalization failed")
	}
}

func TestBootstrapPayloadVector(t *testing.T) {
	req := Registration{IdentityID: "zid_example", DeviceID: "zdev_example", PublicKey: "abc", DisplayName: "九云"}
	expected := "zisee.bootstrap.v1\nzid_example\nzdev_example\nabc\n5Lmd5LqR"
	if string(BootstrapPayload(req)) != expected {
		t.Fatalf("payload: %s", BootstrapPayload(req))
	}
	if string(ChallengePayload(Challenge{ID: "abc", Nonce: "xyz"})) != "zisee.auth.v1\nabc\nxyz" {
		t.Fatal("challenge payload")
	}
}

func TestSignatureAndKeyRestrictions(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := parseKey(der)
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte("signed payload")
	digest := sha256.Sum256(payload)
	signature, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	if !verify(parsed, payload, encoding.EncodeToString(signature)) {
		t.Fatal("valid signature rejected")
	}
	if verify(parsed, []byte("changed"), encoding.EncodeToString(signature)) {
		t.Fatal("tampering accepted")
	}
	if verify(parsed, payload, "!") {
		t.Fatal("invalid signature accepted")
	}
	other, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	otherDER, _ := x509.MarshalPKIXPublicKey(&other.PublicKey)
	if _, err := parseKey(otherDER); err == nil {
		t.Fatal("P384 must be rejected")
	}
}
