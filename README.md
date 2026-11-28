# PaySaathi — White-label Digital Services Platform

PaySaathi is evolving from a single-business recharge and bill-payment app into a multi-tenant, white-label SaaS platform.

## Product direction
- A platform owner can create separate business workspaces for customers.
- Each workspace has its own membership roles, brand name, logo URL, colours, support contacts and subscription record.
- A business owner can switch between workspaces they are explicitly a member of.
- The signed-in workspace branding is applied on the shared PaySaathi web host; verified custom hostnames can resolve their public brand.
- Custom domain ownership can be verified through a server-side DNS TXT challenge. Automatic hostname routing and SSL provisioning are not yet enabled.
- The original public homepage (index.html) remains a separately maintained public marketing page and is intentionally preserved.

## Current architecture
- Static web frontend: HTML, CSS and JavaScript
- Authentication, database and storage: Supabase
- PWA install/update shell and Android project
- Database migrations: supabase/migrations/

## Tenant data isolation status
- 29 operational tables have tenant ownership columns and restrictive tenant-isolation policies.
- Legacy rows in the migrated tables were backfilled and checked; the phase-one and phase-three FK validations passed.
- Database triggers assign the signed-in user's active tenant on supported inserts and reject explicit cross-tenant writes or tenant reassignment.
- These controls are a foundation, not a declaration of full commercial readiness. Existing permissive policies and every app workflow still need end-to-end review, especially admin/staff flows and archived data.
- Workspace plans currently create trial/subscription metadata. There is no live payment-provider checkout, recurring billing, dunning or automated paid-plan activation yet.
- Custom-domain TXT verification proves domain ownership only. Hosting routing, certificate provisioning and tenant-aware production DNS are not yet automated.

## Release boundary
Do not enable paid white-label customer onboarding or real-money multi-tenant use until tenant-aware admin operations, independent buyer onboarding, payment-provider integration, backup/restore drills, comprehensive cross-tenant read/write tests and independent security review are complete.

## Security principles
- Never put Supabase service-role/secret keys in browser code.
- Never store passwords, OTPs, payment credentials or provider secrets in source control.
- Financial balances and payment success must be controlled by trusted server-side logic and verified provider callbacks.
- Keep KYC and regulated financial features disabled until the appropriate licensed provider and compliance controls are configured.
- Enable Supabase leaked-password protection and review remaining SECURITY DEFINER/RLS advisor findings before production launch.
