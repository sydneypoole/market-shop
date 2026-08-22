# Codebase Audit

## Existing Capabilities

- `pages/profile/edit` already uses the supported native `chooseAvatar` button and `nickname` input.
- Privacy authorization is gated through `wx.requirePrivacyAuthorize` and the privacy contract can be opened with `wx.openPrivacyContract`.
- `api/member.updateNickname` saves the authenticated nickname.
- `api/member.uploadAvatar` uploads a local avatar file through the authenticated multipart transport.
- Registration returns a member token before navigation, so profile persistence can be completed within the registration UI without widening the public authentication request to accept file paths or untrusted avatar URLs.

## Chosen Flow

1. Collect and validate privacy authorization, nickname, and local avatar on the registration page.
2. Obtain a fresh `wx.login` code and submit the existing registration request.
3. Persist the returned token.
4. Save nickname through the authenticated membership endpoint.
5. Upload avatar through the authenticated multipart endpoint.
6. Navigate to the storefront only after both profile mutations succeed.

This keeps the public registration endpoint credential-focused while making nickname and avatar part of the user-visible registration transaction. Retry state remains in memory and never stores login codes, claim secrets, or temporary avatar paths in storage.
