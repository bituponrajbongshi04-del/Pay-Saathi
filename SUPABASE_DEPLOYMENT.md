# PaySaathi Supabase deployment and final verification

This branch keeps the existing HTML/CSS page structures and adds an admin-published homepage layer, admin-to-customer notifications, a six-digit password-plus-email-OTP login function, and database policies/storage support.

## 1. Required provider setup

The login OTP Edge Function sends mail through Resend. Before deploying it:

1. Create or use a Resend account.
2. Verify a domain you control and create an API key.
3. Choose a sender address on that verified domain, for example `PaySaathi <no-reply@your-verified-domain.example>`.
4. Never put the Resend key or Supabase service-role key in HTML, GitHub Pages, or browser JavaScript.

## 2. Apply the database migration

From the repository root, link the Supabase project and apply the migration:

```bash
supabase login
supabase link --project-ref hhqcycwfvxoxjizlbivv
supabase db push
```

The migration creates the notification table, private OTP challenge table, branding storage bucket, and policies. It also tightens access policies for existing PaySaathi profile, ticket, message, and notification tables.

**Important prerequisite:** this project migration expects the existing core schema and helper functions used by the current Admin Panel to already exist, including `ps_profiles`, `ps_wallets`, `ps_tickets`, `ps_ticket_messages`, `ps_is_admin()`, and `public.is_paysaathi_admin()`. If Supabase reports one of these as missing, stop and install/reconcile the project's original base/admin setup SQL before retrying. Do not bypass the RLS errors.

## 3. Configure Edge Function secrets and deploy

```bash
supabase secrets set RESEND_API_KEY="YOUR_RESEND_API_KEY" OTP_FROM_EMAIL="PaySaathi <no-reply@your-verified-domain.example>"
supabase functions deploy password-email-otp --no-verify-jwt
```

Supabase supplies `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` to Edge Functions. The function uses the service-role key only server-side to manage short-lived challenges; it never returns that key to the browser.

The function:
- Verifies the account password with Supabase Auth.
- Sends a cryptographically generated six-digit code through Resend.
- Stores only a hash of the code, with a five-minute expiry and five-attempt limit.
- Re-checks the password before returning a Supabase session.
- Consumes the code once and does not persist passwords or OTP plaintext.

## 4. Signup email OTP length

The repository's `supabase/config.toml` sets Supabase Auth email OTP length to six digits. Apply/sync the Auth config using your Supabase CLI workflow, or set the Email OTP length to `6` in Supabase Dashboard → Authentication → Email. Confirm the signup email template includes the numeric token variable `{{ .Token }}`; do not use only the confirmation URL if you want a code displayed.

## 5. Admin website controls

After deployment, sign in with the existing authorized admin account and open **Website Builder**. It can publish brand identity, logo/favicon, hero copy/image/button, colours, layout, buttons/navigation, visibility of homepage sections, section copy, benefit cards, journey steps, custom sections and footer settings. Service Manager controls the enabled service catalogue. Platform Settings controls maintenance, announcements, support contacts and notification copy.

The public homepage reads the published branding and public site status, applies the saved settings without replacing the original page layout, and loads enabled service catalogue entries. Customer dashboard reads the same branding and displays admin announcements.

The Settings page can send a personal notification to one account or a global notification to all signed-in users. These are stored in `ps_notifications` and shown in the customer's notification modal.

## 6. Final tests before merging

- Create a fresh test account; confirm the email contains a six-digit signup code and signup verification creates a session.
- Sign in with a valid password; confirm a six-digit login code arrives, wrong/expired/reused codes are rejected, and a correct code routes by role.
- Refresh, close/reopen Chrome, and switch mobile/desktop mode; confirm the session remains until manual logout.
- Publish a temporary homepage heading/theme from Website Builder; confirm it appears on `index.html` after reload and the original responsive layout still works.
- Add/disable a catalogue service; confirm the public homepage reflects the enabled service list.
- Send one global and one user-targeted notification; confirm the intended customer sees it in the dashboard notification modal.
- Test support-agent assignment, ticket visibility and replies using separate customer and agent test accounts.
- Toggle maintenance mode and confirm the public homepage/dashboard show the maintenance state while the admin panel remains reachable.
- Confirm no test wallet credit or payment UI creates a real balance or claims a real provider payment succeeded.

## 7. Integrations that are not supplied by this repository

The gateway manager stores gateway metadata only. Real recharge, bill payment, AEPS, money transfer, payout and card/UPI settlement require contracts, API credentials, provider-side onboarding, server-side order creation and signed webhook verification. Do not enable live money movement until those integrations are implemented and tested.
