# Storefront Query Contracts

## Scenario: Authenticated detail queries and public rule projection

### 1. Scope / Trigger

- Trigger: any change to member order detail, order proof listing, after-sale detail, after-sale proof listing, invitation lookup, or active distribution-rule projection.
- Controllers translate the authenticated member ID into an application use-case call. They must not query MyBatis mappers or reconstruct authorization rules.
- Public rule output is a read-only projection. It must not expose draft, disabled, future, expired, or superseded rule versions.

### 2. Signatures

```java
CommerceUseCase.OrderDetail order(long userId, long orderId);
List<OrderProofUseCase.ProofView> listUser(long userId, long orderId);

AfterSaleUseCase.View afterSale(long userId, long afterSaleId);
List<AfterSaleProofUseCase.ProofView> listUser(long userId, long afterSaleId);

MembershipUseCase.InvitationView currentInvitation(long userId);
List<MembershipUseCase.RuleView> activeRules();
```

```http
GET /api/v1/orders/{orderId}
GET /api/v1/orders/{orderId}/proofs
GET /api/v1/order-proofs/{proofId}/download

GET /api/v1/after-sales/{afterSaleId}
GET /api/v1/after-sales/{afterSaleId}/proofs
GET /api/v1/after-sale-proofs/{proofId}/download

GET /api/v1/membership/invitation
GET /api/v1/rules/active
```

### 3. Contracts

- Order detail and proof access are allowed only to the buyer, the immutable direct superior, or an explicitly permitted admin path.
- After-sale detail and proof access are allowed only to the applicant, the order's immutable direct superior, or an explicitly permitted admin path.
- Authorization is checked before proof metadata is returned and again before a short-lived download URL is signed.
- Every proof list, preview, or download action appends an attributable audit record using the real member/admin actor.
- `currentInvitation` is read-only and returns no invitation when one does not exist. Page loading must never create or regenerate a code.
- `activeRules` returns only `ACTIVE` rules whose effective window includes the current time. When multiple rows share a `ruleCode`, only the latest effective/versioned row is projected.
- The public rules endpoint exposes displayable configuration only. Rule evaluation, qualification, points, and commission remain server-authoritative.
- All endpoints use the common `ApiResponse` envelope and integer-fen money fields.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Missing member session on protected query | HTTP 401; no data or signed URL |
| Authenticated but unrelated member | HTTP 403 with stable access-denied code |
| Order, after-sale, proof, or invitation is absent | HTTP 404 or nullable invitation result according to the use-case contract |
| Order/after-sale relationship changed in request data | Ignore client claims; resolve ownership from persisted aggregate data |
| Proof storage signing fails | Stable signing failure; no object key or vendor detail |
| Rule is draft, disabled, future, or expired | Excluded from the public projection |
| Multiple active versions overlap | Return only the latest applicable version per `ruleCode` |

### 5. Good/Base/Bad Cases

- Good: an order buyer opens the detail page, sees persisted line snapshots and logistics, lists proof metadata, and requests a fresh five-minute preview URL.
- Good: the direct superior reviews an after-sale detail while an unrelated member receives 403.
- Base: a member has no invitation; the page displays an explicit empty state and creates one only after a user action.
- Base: no active distribution rule exists; the storefront shows a retryable/empty configuration state rather than fabricated thresholds.
- Bad: create an invitation during a GET/page-mount flow, return every historical rule version, trust a `userId` query parameter, or return a permanent RustFS URL.

### 6. Tests Required

- Application tests cover buyer/applicant access, direct-superior access, unrelated-member denial, absent resources, and proof-list audit actors.
- Rule projection tests cover inactive, future, expired, overlapping, and latest-version selection.
- Invitation tests prove lookup is read-only and absence does not call a create/regenerate port.
- Interface/contract tests verify protected routes require a member session while `/api/v1/rules/active` remains public.
- Storefront tests verify detail-route wiring, short-lived proof download flow, dynamic rule rendering, session-expiry redirect, and post-mutation reload.

### 7. Wrong vs Correct

#### Wrong

```java
@GetMapping("/orders/{id}/proofs")
List<OrderProofRow> proofs(@PathVariable long id) {
    return proofMapper.selectByOrderId(id);
}
```

```java
@GetMapping("/membership/invitation")
InvitationView invitation() {
    return membershipUseCase.createInvitation(currentUserId());
}
```

#### Correct

```java
@GetMapping("/orders/{id}/proofs")
ApiResponse<List<OrderProofView>> proofs(@PathVariable long id) {
    return ApiResponse.ok(orderProofUseCase.listUser(currentUserId(), id));
}
```

```java
@GetMapping("/membership/invitation")
ApiResponse<InvitationView> invitation() {
    return ApiResponse.ok(membershipUseCase.currentInvitation(currentUserId()));
}
```

This keeps query authorization and version selection in the application layer, preserves read-only HTTP semantics, and prevents storage credentials or internal persistence models from crossing the interface boundary.
