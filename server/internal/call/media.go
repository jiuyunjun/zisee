package call

import "context"

type Description struct {
	Sequence int    `json:"sequence"`
	Type     string `json:"type"`
	SDP      string `json:"sdp"`
}

type MediaSnapshot struct {
	Call         Call          `json:"call"`
	Descriptions []Description `json:"descriptions"`
}

type MediaStore interface {
	SendDescription(context.Context, string, string, string, string, string) (int, error)
	SyncDescriptions(context.Context, string, string, int) (MediaSnapshot, error)
}
