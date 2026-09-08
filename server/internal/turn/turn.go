// Package turn issues short-lived TURN credentials from Cloudflare.
//
// The API token never leaves the server: clients receive only a generated
// username/credential pair that expires on its own, per ARCHITECTURE.md §9.3.
package turn

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

const (
	endpoint     = "https://rtc.live.cloudflare.com/v1/turn/keys/%s/credentials/generate"
	stunFallback = "stun:stun.cloudflare.com:3478"
	maxResponse  = 16 << 10
)

// ErrUnavailable is returned when Cloudflare cannot be reached or refuses the
// request. Callers degrade to STUN rather than failing the call.
var ErrUnavailable = errors.New("turn_unavailable")

// IceServer mirrors the WebRTC RTCIceServer shape the client consumes directly.
type IceServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

type Client struct {
	keyID, apiToken string
	http            *http.Client
}

func New(keyID, apiToken string) *Client {
	return &Client{keyID: keyID, apiToken: apiToken, http: &http.Client{Timeout: 5 * time.Second}}
}

// Credentials returns one ICE server entry carrying Cloudflare's STUN and TURN
// URLs plus a credential valid for ttl.
func (c *Client) Credentials(ctx context.Context, ttl time.Duration) (IceServer, error) {
	seconds := int(ttl.Seconds())
	if seconds < 60 || seconds > 86400 {
		return IceServer{}, errors.New("ttl must be 60..86400 seconds")
	}
	body, err := json.Marshal(map[string]int{"ttl": seconds})
	if err != nil {
		return IceServer{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost,
		fmt.Sprintf(endpoint, c.keyID), bytes.NewReader(body))
	if err != nil {
		return IceServer{}, err
	}
	request.Header.Set("Authorization", "Bearer "+c.apiToken)
	request.Header.Set("Content-Type", "application/json")
	response, err := c.http.Do(request)
	if err != nil {
		// The URL carries the key id and the error can echo it; never wrap it.
		return IceServer{}, ErrUnavailable
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusCreated {
		io.Copy(io.Discard, io.LimitReader(response.Body, maxResponse))
		return IceServer{}, ErrUnavailable
	}
	var payload struct {
		IceServers IceServer `json:"iceServers"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, maxResponse)).Decode(&payload); err != nil {
		return IceServer{}, ErrUnavailable
	}
	if len(payload.IceServers.URLs) == 0 || payload.IceServers.Username == "" || payload.IceServers.Credential == "" {
		return IceServer{}, ErrUnavailable
	}
	return payload.IceServers, nil
}

// Fallback is the public STUN-only configuration used when no TURN key is
// provisioned or Cloudflare is unreachable. Direct connections still work.
func Fallback() IceServer { return IceServer{URLs: []string{stunFallback}} }
