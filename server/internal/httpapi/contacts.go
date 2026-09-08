package httpapi

import (
	"net/http"
	"zisee/server/internal/call"
)

func (s *Server) contacts(w http.ResponseWriter, r *http.Request) {
	session, _, ok := s.authorize(w, r)
	if !ok {
		return
	}
	store, ok := s.store.(call.ContactStore)
	if !ok {
		writeError(w, 503, "temporarily_unavailable")
		return
	}
	var value any
	var err error
	switch r.Method {
	case "GET":
		var contacts []call.Contact
		contacts, err = store.ListContacts(r.Context(), session.IdentityID)
		value = map[string]any{"contacts": contacts}
	case "POST":
		value, err = store.CallContact(r.Context(), session.IdentityID, r.PathValue("peerId"))
	case "DELETE":
		err = store.RemoveContact(r.Context(), session.IdentityID, r.PathValue("peerId"))
		value = map[string]bool{"removed": true}
	}
	if err != nil {
		s.callFail(w, err)
		return
	}
	writeJSON(w, 200, value)
}
