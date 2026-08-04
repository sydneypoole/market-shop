# State Management

Use local refs for form and presentation state, URL parameters for shareable navigation state, and the backend for business state. Admin authentication remains isolated from member authentication: admin uses an HttpOnly cookie; the miniprogram client stores and sends `market-shop-user-token` as a request header.

Never infer a successful business transition solely from a click. After superior confirmation, admin review, shipping, receiving, rule publication, or aftersale processing, reload the server representation.

Do not persist passwords, WeChat codes, proof URLs, complete order responses, RBAC decisions, or point balances in browser storage.

Derived values such as totals and available actions use computed state. A route guard may improve navigation but does not replace backend authorization.
