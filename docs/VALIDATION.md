# Verification record

Verified locally on 6 September 2026. This record distinguishes tested application behavior from deployment work that still requires the operator’s environment.

## Executed checks

| Check | Result |
|---|---|
| Maven compile, integration tests and WAR packaging | PASS — `mvn verify`, Java release target 17 |
| H2 integration suite | PASS — 11 scenarios, 0 failures, 0 errors |
| MySQL 8.0.46 integration suite | PASS — the same 11 scenarios, 0 failures, 0 errors |
| JavaScript syntax | PASS — Node `--check` |
| MySQL backup and restoration | PASS — all 14 tables matched using extended checksums after restore to a separate database |
| Local browser client flow | PASS — registration, dashboard, trip creation, generated three-day itinerary, expense entry and over-budget total |
| Local browser admin flow | PASS — role-based access, management screen, experience settings save and name/email user search |
| Recommendations and comparison | PASS — ranked results, preserved selection state, two-destination comparison |
| Mobile layout | PASS for sampled 390 × 844 viewport — destination filters/cards, navigation and recommendations; no horizontal page overflow in the measured recommendation view |
| Browser console | No errors observed during the sampled workflows |

## Automated scenarios

1. JSP page rendering, security headers and health response.
2. Trip lifecycle: generation, budget edits, packing, itinerary validation, wishlist idempotence, completion, cancellation, feedback uniqueness and cross-user isolation.
3. CSRF rejection, invalid inputs, password change and session revocation.
4. Admin access controls, account disable/enable, registration switch and audit records.
5. Login attempt throttling.
6. Photo normalization, private retrieval, invalid-photo rejection and deletion ownership.
7. Twenty concurrent authenticated catalog reads, all successful.
8. Admin catalog creation/publication/unpublication and feedback response round-trip.
9. Admin pagination across 51 matching fixture accounts, including the second page and invalid page rejection.
10. Shared MySQL sessions across two Tomcat instances, token hashing and cross-instance logout revocation.
11. Twelve concurrent users with separate accounts and trips, mixed reads/writes and expense isolation.

The actual runner used the installed JDK 25 compiler with `--release 17`, embedded Tomcat 10.1.59, H2 2.3.232 and an isolated MySQL 8.0.46 process. The CI configuration targets JDK 17, but a hosted CI run has not been executed here.

## Production gates still pending

- Deploy the supplied container to the selected Vercel account and connect the hosted MySQL service using verified TLS. A provider vercel.app hostname is sufficient; buying a domain is not a gate.
- Repeat the specified 12-user workload on hosted staging, including cold starts, remote database latency and proxy rate-limit behavior. Local workload evidence is recorded below.
- Running the Docker image and reverse-proxy configuration in the target infrastructure; Docker was not available in this session.
- Deployment-specific vulnerability scan, secret configuration, monitoring and scheduled encrypted backups.
- Operator review of sample destinations, estimated prices, illustrative images, contact details and user-facing policies.
- Full assistive-technology and cross-browser accessibility certification; sampled responsive checks are not equivalent to a formal audit.

No production deployment has been made. The tests substantiate the behaviors above; they do not prove an absence of all possible bugs, security vulnerabilities or performance issues. Do not describe the project as production-certified until the remaining environment-specific gates pass.

## Shared-session revision verification

The latest H2 run passed all 11 scenarios with zero failures/errors, including cross-instance session sharing/logout revocation and 12 concurrent users executing 108 mixed HTTP requests. Warm local p95 was 103 ms; maximum 152 ms. The latest MySQL 8.0.46 run also passed all 11 scenarios against a fresh schema-version-2 database. Its 108-request mixed workload had zero failures, p95 172 ms and maximum 239 ms. A fresh dump restored into a separate database matched all 14 tables by extended checksum. Docker/Vercel and hosted Aiven have not yet been exercised.



