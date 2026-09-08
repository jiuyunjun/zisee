package call

import "context"

type Contact struct {
	IdentityID  string `json:"identityId"`
	DisplayName string `json:"displayName"`
}

type ContactStore interface {
	ListContacts(context.Context, string) ([]Contact, error)
	CallContact(context.Context, string, string) (Call, error)
	RemoveContact(context.Context, string, string) error
}
