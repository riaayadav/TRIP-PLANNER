# Packages, administration and demo payments

Administrators now land in a dedicated Operations overview with separate navigation for packages, hotels, transportation, bookings/payments and the existing user/destination/review controls. The client dashboard remains a personal travel workspace, with Explore packages and My bookings added.

## Admin workflow

1. Add hotels with addresses, room descriptions and amenities.
2. Add transportation services with a mode, route and service details. Mixed mode can describe a combined journey.
3. Create a package: select its destination, hotel and transportation, enter a per-person INR price, duration, party-size limit, cover image, inclusions, exclusions, itinerary and cancellation terms.
4. Save as Draft or Published. Published packages appear to clients only while their destination, hotel and transportation are active.
5. Review booking requests and move them to Confirmed, Cancelled or Completed as appropriate. Completion requires the end date to have passed.
6. Record simulated receipts/refunds and review each customer's ledger. Payment status is calculated from recorded amounts, not an editable label.

Deleting a package removes it from the catalog while preserving historical bookings. Hotels/transport can be archived and restored; published packages must first be unpublished. Existing bookings retain a JSON snapshot of the package and supplier details and the original server-calculated total.

## Client workflow

Explore packages → View package → review inclusions and terms → select dates and travelers → Request demo booking. View requests and payment histories under My bookings. Pending requests can be cancelled; confirmed cancellations require the team. Simulate full payment records a demo receipt only.

## Simulation boundary

No gateway is connected, no money moves, and no hotel or transport supplier receives a reservation. All new payment entries are for demonstration. Before real payments are enabled, replace/disable the demo-payment endpoint and simulated ledger forms, add provider-signed webhook verification and provider transaction identifiers, and reconcile payment results server-side. Do not treat browser redirects as proof of payment.

The local database contains one published demo package, one draft, a fictional hotel and transportation service, and three demo bookings showing unpaid, partial and paid states. Demo fixtures are local data, not embedded in the production source or WAR.

## Storage and validation

Schema migration 3 adds hotels, transport_options, travel_packages, package_bookings and booking_payments. Start with DB_INITIALIZE=true once to migrate an existing version-2 database; DB_INITIALIZE=false requires version 3 afterward. Back up application data before applying migrations to an important database. The hosted database was not changed in this work.

Validation: 18 Java integration tests passed (17 existing workflows plus a package lifecycle test on a separate test server). The package lifecycle also passed against disposable MySQL 8.0.46. Eight JavaScript tests passed. Browser checks confirmed role-specific navigation, the package editor and its save action, and sample payment statuses.

The package test covers draft visibility, permissions, stale package versions, party-size limits, duplicate booking/payment requests, ownership, frozen booking details, partial/full/refunded status, excessive receipts/refunds, cancellation transitions and catalog deletion preserving bookings.
