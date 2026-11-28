# PaySaathi — White-label Digital Services Platform

PaySaathi is evolving from a single-business recharge and bill-payment app into a multi-tenant, white-label SaaS platform.

## Product direction
- A platform owner can create separate business workspaces for customers.
- Each workspace has its own membership roles, brand name, logo URL, colours, support contacts and subscription record.
- A business owner can switch between workspaces they are explicitly a member of.
- Custom domain ownership can be verified through a server-side DNS TXT challenge. Automatic hostname routing and SSL provisioning are not yet enabled.
- The original public homepage (index.html) remains a separately maintained public marketing page.

## Current architecture
- Static web frontend: HTML, CSS and JavaScript
- Authentication, database and storage: Supabase
- PWA install/update shell and Android project
- Database migrations: supabase/migrations/

## Important release boundary
The tenant identity, membership, branding, subscription metadata and audit-log foundation is installed. The existing wallet, transaction, KYC, support, staff and service tables have **not yet been migrated to tenant_id isolation**. The platform is therefore not ready for commercial white-label customer onboarding or real-money multi-tenant use. Do not enable paid tenant access until the tenant data-isolation migration, tenant-aware admin operations, domain verification, payment-provider integration, backup/restore drills and independent security tests are complete.

## Security principles
- Never put Supabase service-role/secret keys in browser code.
- Never store passwords, OTPs, payment credentials or provider secrets in source control.
- Financial balances and payment success must be controlled by trusted server-side logic and verified provider callbacks.
- Keep KYC and regulated financial features disabled until the appropriate licensed provider and compliance controls are configured.
