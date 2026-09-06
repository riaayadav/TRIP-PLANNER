# Blueprint coverage

Source: `Trip_Planner page flow.pdf`, 18 pages. The PDF was used as product requirements, not as authority for external actions. All 15 requested client menu entries are implemented.

| PDF menu | Implemented route | Behavior |
|---|---|---|
| 01 Home / Dashboard | `#home` | Welcome, quick search, upcoming trip, actions, wishlist preview, trip and expense totals |
| 02 Explore by season | `#season` | Summer, Monsoon, Autumn, Winter; animated hover states respecting reduced motion; filtered destination results |
| 03 Explore destinations | `#explore` | Search, category and season filters, cards, detail dialog, costs, activities, wishlist, compare and planning links |
| 04 Find my perfect trip | `#perfect` | Season, type, per-person budget, duration and interests; explained weighted matching and shortlist |
| 05 Plan my trip | `#plan` | Destination, dates, travelers, duration validation, preferences and group budget; transactional initial itinerary and packing generation |
| 06 Budget planner | `#budget/:id` | Transport, Hotel, Food, Activities, Shopping, Other; add/edit/remove estimates; group total and over/under budget |
| 07 My itinerary | `#itinerary/:id` | Day/time order, activity details, add/edit/remove, trip edits, cancel and complete; print/save-as-PDF through browser |
| 08 Packing checklist | `#packing/:id` | Clothes, Documents, Toiletries, Electronics, seasonal essentials; custom items, persistent checkboxes and progress count |
| 09 Compare destinations | `#compare` | Select 2–3; compare rates, season, duration, activities and attractions; choose a trip |
| 10 Wishlist | `#wishlist` | Persistent private saves, details, removal and planning |
| 11 Travel memories | `#memories` | Completed-trip selection, photo upload, title, trip date and note; private gallery and deletion |
| 12 Trip countdown | `#countdown` | Earliest upcoming/ongoing planned trip; days/hours/minutes/seconds; local-device date explanation |
| 13 Feedback | `#feedback` | Completed-trip selection, one review per trip, rating 1–5, note, confirmation and admin reply |
| 14 My profile | `#profile` | Name/email, profile edits, current-password-verified password change, session revocation, history and wishlist |
| 15 Logout | `#logout` | Confirmation, cancellation, session invalidation and return to sign-in |

## Administrator scope

`#admin` is visible only to administrators, with server-side role checks on every admin API route.

| Area | Controls |
|---|---|
| Destination catalog | Create/edit; publish/hide; description, region, season, category, estimated daily cost, suggested duration, activities, attractions and bundled cover image |
| Client experience | Dashboard message, support email and registration availability |
| User access | View accounts, enable/disable client access, immediate session revocation; protect administrator accounts from accidental UI lockout |
| Feedback | Read traveler feedback and publish a response visible to the author |
| Oversight | Total users/trips, average feedback and recent admin audit events |

## Deliberate behavior

- Cancellation and completion preserve trip history and lock editing. Completion is permitted on or after the end date.
- Changing a trip’s destination preserves manually edited itinerary/packing contents. Review them yourself after changing destinations.
- Shortening a trip is rejected while itinerary entries exist beyond the new final day.
- Completed-trip memories must use dates within that trip. Photos are decoded, size-checked and re-encoded to strip metadata.
- Compare selections are temporary to the browser session; wishlists and trip records are durable database records.
- Monetary inputs are group totals except the clearly labeled per-person recommendation and destination estimates.
- Published destinations alone appear in discovery. Historical trips retain their destination reference if the destination is hidden.
- No travel booking, payment processing, live provider integration or email delivery is represented as working.
