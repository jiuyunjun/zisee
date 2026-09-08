package call

import (
	"context"
	"strings"
	"zisee/server/internal/identity"
)

type Description struct {
	Sequence int    `json:"sequence"`
	Type     string `json:"type"`
	SDP      string `json:"sdp"`
}

type MediaSnapshot struct {
	Call         Call          `json:"call"`
	Descriptions []Description `json:"descriptions"`
	Candidates   []Candidate   `json:"candidates"`
}

type MediaStore interface {
	SendCandidates(context.Context, string, string, []Candidate) error
	SendDescription(context.Context, string, string, string, string, string) (int, error)
	SyncDescriptions(context.Context, string, string, int) (MediaSnapshot, error)
}

// Candidates are cumulative and append-only, making reconnect retries idempotent.
type Candidate struct {
	SDP   string `json:"candidate" firestore:"candidate"`
	Mid   string `json:"sdpMid" firestore:"sdpMid"`
	Index int    `json:"sdpMLineIndex" firestore:"sdpMLineIndex"`
}

func ValidateCandidates(next, previous []Candidate) error {
	if len(next) == 0 || len(next) > 32 {
		return identity.ErrInvalid
	}
	for _, c := range next {
		if !strings.HasPrefix(c.SDP, "candidate:") || len(c.SDP) > 1024 || !printableASCII(c.SDP) || len(c.Mid) > 32 || !printableASCII(c.Mid) || c.Index < 0 || c.Index > 8 {
			return identity.ErrInvalid
		}
	}
	for i, c := range previous {
		if i >= len(next) {
			break
		} // An older retry is a harmless no-op.
		if next[i] != c {
			return ErrTransition
		}
	}
	return nil
}

func printableASCII(value string) bool {
	for _, r := range value {
		if r < 32 || r > 126 {
			return false
		}
	}
	return true
}
