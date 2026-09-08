package call

import (
	"context"
	"errors"
	"strings"
	"zisee/server/internal/identity"
)

type Description struct {
	Sequence int    `json:"sequence"`
	Type     string `json:"type"`
	SDP      string `json:"sdp"`
}

var ErrGeneration = errors.New("stale_media_generation")

type MediaSnapshot struct {
	Generation   int           `json:"generation"`
	Call         Call          `json:"call"`
	Descriptions []Description `json:"descriptions"`
	Candidates   []Candidate   `json:"candidates"`
}

type MediaStore interface {
	SendCandidates(context.Context, string, string, []Candidate, ...int) error
	RestartMedia(context.Context, string, string, int) (int, error)
	SendDescription(context.Context, string, string, string, string, string, ...int) (int, error)
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

// Missing generation remains generation zero for existing clients.
func Generation(value []int) int {
	if len(value) == 0 {
		return 0
	}
	return value[0]
}

const MaxMediaGeneration = 64
