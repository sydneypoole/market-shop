# State Management

Use local refs for form and presentation state, URL parameters for shareable navigation state, Pinia for storefront cart/session state, and the backend for business state. Admin authentication remains isolated from member authentication and uses a separate storage key and token header name.

Never infer a successful business transition solely from a click. After superior confirmation, admin review, shipping, receiving, rule publication, or aftersale processing, reload the server representation.

Persist only the minimum session token and non-sensitive cart data. Do not persist passwords, WeChat codes/state, proof URLs, complete order responses, RBAC decisions, or point balances in local storage.

Derived values such as totals and available actions use computed state. A route guard may improve navigation but does not replace backend authorization.
