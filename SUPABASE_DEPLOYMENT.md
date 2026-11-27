# PaySaathi Supabase deployment and final verification

This branch preserves the existing page structures and connects the homepage to the already-present PaySaathi CMS, service catalogue, support system and notifications. It adds a dedicated six-digit password-plus-email-OTP login challenge table and tightens profile update permissions.

## 1. Required provider setup

The new login OTP Edge Function sends mail through Resend. Before deploying it:

1. Create or use a Resend account.
2. Verify a domain you control and create an API key.
3. Choose a sender address on that verified domain, e.g. PaySaathi <no-reply@your-domain.example>.
4. Add RESEND_API_KEY and OTP_FROM_EMAIL as Supabase Edge Function secrets in Project Settings → Edge Functions → Secrets.
5. Never put the Resend key or Supabase service-role key in HTML, GitHub Pages, or browser JavaScript. Do not paste secrets into chat.

## 2. Apply the database migration

The migration was reconciled against the live PaySaathi database. It is additive and preserves existing service, branding, support, wallet and notification data.

```bash
supabase login
supabase link --project-ref hhqcycwfvxoxjizlbivv
supabase db push
```

It creates the private OTP challenge table, prevents direct customer edits to profile roles/status, grants the existing admin-only branding bucket delete permission, and retains the current personal/global notification schema and policies.

## 3. Deploy the login OTP Edge Function

After adding the two Resend secrets:

```bash
supabase functions deploy password-email-otp --no-verify-jwt
```

The function verifies the account password, sends a cryptographically generated six-digit code through Resend, stores only a hash, expires it after five minutes, limits attempts, re-checks the password before returning a session, and consumes the code once.

Supabase supplies SUPABASE_URL, SUPABASE_ANON_KEY, and SUPABASE_SERVICE_ROLE_KEY to Edge Functions. The service-role key stays server-side.

## 4. Signup email OTP length

The repository supabase/config.toml sets Supabase Auth email OTP length to six digits. Set Authentication → Email → OTP length to 6 in the Supabase Dashboard as well. Confirm the signup email template includes the numeric token variable {{ .Token }}; do not use only the confirmation URL if you want a code displayed.

## 5. Admin website controls

After the updated site is deployed, sign in with the existing authorized admin account and open Website Builder. It can publish brand identity, logo/favicon, hero copy/image/button, colours, layout, buttons/navigation, visibility of homepage sections, section copy, benefit cards, journey steps, custom sections and footer settings. Service Manager controls the enabled service catalogue. Platform Settings controls maintenance, announcements, support contacts and notification copy.

The public homepage reads the published branding and public site status, applies saved settings without replacing the original responsive layout, and loads enabled service catalogue entries.

The Settings page can send a personal notification to one account or a global notification to all signed-in users. These are stored in the existing ps_notifications table and shown in the customer dashboard notification modal.

## 6. Final tests before merging

- Create a fresh test account; confirm the email contains a six-digit signup code and signup verification creates a session.
- Sign in with a valid password; confirm a six-digit login code arrives, wrong/expired/reused codes are rejected, and a correct code routes by role.
- Refresh, close/reopen Chrome, and switch mobile/desktop mode; confirm the session remains until manual logout.
- Publish a temporary homepage heading/theme from Website Builder; confirm it appears on index.html after reload and the original responsive layout still works.
- Add/disable a catalogue service; confirm the public homepage reflects the enabled service list.
- Send one global and one user-targeted notification; confirm the intended customer sees it in the dashboard notification modal.
- Test support-agent assignment, ticket visibility and replies using separate customer and agent test accounts.
- Toggle maintenance mode and confirm the public homepage/dashboard show the maintenance state while the admin panel remains reachable.
- Confirm no test wallet credit or payment UI creates a real balance or claims a real provider payment succeeded.

## 7. Integrations that are not supplied by this repository

The gateway manager stores gateway metadata only. Real recharge, bill payment, AEPS, money transfer, payout and card/UPI settlement require contracts, API credentials, provider-side onboarding, server-side order creation and signed webhook verification. Do not enable live money movement until those integrations are implemented and tested.
