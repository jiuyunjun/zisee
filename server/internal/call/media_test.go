package call

import (
	"strings"
	"testing"
)

func TestCandidateBoundsAndReplay(t *testing.T) {
	a := Candidate{SDP: "candidate:1 1 udp 1 192.0.2.1 1234 typ host", Mid: "0", Index: 0}
	b := a
	b.SDP = "candidate:2 1 udp 1 192.0.2.2 1234 typ relay"
	for _, pair := range []struct{ next, old []Candidate }{{[]Candidate{a}, nil}, {[]Candidate{a, b}, []Candidate{a}}, {[]Candidate{a}, []Candidate{a, b}}} {
		if err := ValidateCandidates(pair.next, pair.old); err != nil {
			t.Fatal(err)
		}
	}
	for _, next := range [][]Candidate{nil, make([]Candidate, 33), {{SDP: "candidate:x\x01"}}, {{SDP: a.SDP, Mid: "\x00"}}, {b}, {{SDP: "candidate:" + strings.Repeat("x", 1024)}}, {{SDP: "candidate:x\r\n"}}, {{SDP: a.SDP, Index: -1}}, {{SDP: a.SDP, Index: 9}}} {
		if ValidateCandidates(next, []Candidate{a}) == nil {
			t.Fatal("accepted invalid or rewritten candidates")
		}
	}
}
