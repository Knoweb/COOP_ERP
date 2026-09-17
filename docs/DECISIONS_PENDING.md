# DECISIONS_PENDING.md — doc 10 Open Items Register, v0.4 (18 September 2026)

This file is the Markdown source of document 10, the Open Items Register. It replaces v0.3 (10 September) and the baseline v0.2 (14 August). The Word copy in the requirements baseline folder on Drive is its published form. LIVE DOCUMENT: it carries every open decision and open action from the requirements baseline and the design phase, grouped by who decides, each with the assumption the work proceeds on. It is reviewed and updated at every Stage 2 gate and at the Stage 4 and 5 reviews; a change here is a new version of doc 10, recorded in the register (doc 00).

**Rule for code.** An open item never blocks design or build. Build to the assumption in the "Assumed / proposed" column, cite the item id in a comment (`// assumes doc 10 E-05`), and never decide silently. A decision that differs from its assumption is handled as a change request against the documents named in the Source column.

## 1. Status of the baseline items

The seven items the baseline register (v0.2) said could still change the requirements, with what happened to each.

| Baseline item | Where it went | Status |
|---|---|---|
| Till authentication: PIN (recommended) vs password | Designed as PIN with Argon2id hashing and lockout policy (doc 19 §2, doc 26 §4.3); parameters in E-05 below | Embodied in design; formal confirmation folded into E-05 |
| RTO 4–8 h / RPO 15 min target | Carried as the planning basis in doc 14 §4 and doc 36 (to write); a local-hosting choice reopens it | Accept as stated (F-14); revisit only if F-01 chooses local hosting |
| 10,000-shop / 3-year growth envelope | Adopted as the capacity planning basis (ADR-22; doc 14 §6) | Closed: adopted |
| Minimum connectivity go-live gate values | Carried as `location.connectivity_spec_met`, a gate for location activation (doc 18, doc 21 §4.3) | Accept as stated (F-16) |
| Helpdesk coverage hours: extended + on-call vs full 24×7 | Cost decision for the Federation; doc 38 (to write) assumes extended + on-call | Open (F-02) |
| Report catalogue finalisation workshop | Pipeline built so definitions land as data; twelve placeholders seeded (doc 28) | Open (P-09): not yet scheduled |
| Ratification of the 13 standing defaults | Each default is now embodied in a design document (Section 2); the sitting has not been held | Open (F-17): table as one sitting |

## 2. The 13-item ratification batch

Presented to stakeholders as defaults that stand unless objected to. Each has design rationale in doc 09 and is now implemented in the named design document; the ratification sitting remains to be held (F-17).

| # | Default | Now embodied in | Note |
|---|---|---|---|
| 1 | G-02 deviation = per-bill discount only, no standing shop price override | Doc 23 (rules as the sole reduction; `BILL_THRESHOLD` rule kind) | Superseded in wording by ADR-14: all reductions come from rules; no manual per-bill discount either |
| 2 | Weighted-average costing as an entity-level policy, never per-transaction | Doc 25 §3.3 | Stands |
| 3 | Repack reversal permitted only while output stock remains unsold | Doc 25 §3.6 (output lot untouched) | Stands |
| 4 | Recipe-first repacking; ad-hoc conversion needs higher permission | Doc 25 (recipes); ad-hoc conversion not built in v1 | Stands; ad-hoc path deferred to doc 20 Appendix A |
| 5 | Recipes shareable via the same promote-to-shared flow as SKUs | Not yet designed (recipes are entity-owned in doc 25) | Open (E-13): confirm whether recipe sharing is wanted in v1 |
| 6 | Cheque receipts release credit headroom provisionally; bounce = reversal + flag | Doc 24 §3.7 and §4.6 (cheque sub-lifecycle; bounce reverses settlements; exposure recomputed) | Stands |
| 7 | Phase-1 customer credit: no interest, no guarantors | Doc 27 §1 | Stands |
| 8 | NIC optional, and only for customers granted credit | Doc 27 §3.1 (hash plus last four when credit granted) | Stands; wording with Federation legal (L-06) |
| 9 | Offline write-offs captured as drafts, approved online | Doc 25 §4.3; doc 26 §3.8 | Stands |
| 10 | Idle-lock defaults: till 2–3 min / back office 10–15 min / admin 5 min | Doc 21 §7 (2 / 3 / 10 / 5) | Stands |
| 11 | Cash-tender rounding to nearest rupee; card/account tenders exact | Doc 23 §3.5; doc 26 §3.3 | Stands |
| 12 | English short name mandatory on every SKU | Doc 22 §3.1 (B-I1) | Stands |
| 13 | No discretionary POS discount of any kind — rule engine only (ADR-14) | Doc 23; doc 26 §1 | Stands |

## 3. Open items by decider

### 3.1 Programme (Chamin)

| Ref | Item | Assumed / proposed | Needed by | Source |
|---|---|---|---|---|
| P-01 | Name the catalogue data steward and the cleansing workstream owner (T-03 critical path) | Unnamed; M2 steward tooling designed for them | Before pilot catalogue assembly | Doc 16 T-03; 22 |
| P-02 | Twelve-month success measures (S-10) | Not defined | Before build sign-off | Doc 16 S-10 |
| P-03 | Team allocation: platform pair, module developers, till track | Tentative; fresh graduates and trainees; Sprint 0 about three weeks | Now | Doc 20 DR-7; 19A |
| P-04 | Re-plan Stage 2 pace after 21/21A are measured | Doc 20 v0.3 dates are unmeasured estimates | Week 2 gate | Doc 20 §6 |
| P-05 | Tax advisor review remains advisory; risk owned by the programme | Decisions N-1 to N-6 stand | – | 24B |
| P-06 | Receipt legal content confirmation when the advisor's view arrives | Doc 26 §3.4 | Before pilot | Doc 26 DR-6 |
| P-07 | NFR-UPD-040 (LAN-shared updates) change request to doc 06 | Deferred; management-agent path | Baseline v0.3 sign-off | Doc 17 C10 |
| P-08 | Register the Federation as a verified Android developer | Do now; low cost | Before pilot | Doc 31 DR-3 |
| P-09 | Report catalogue workshop date | Stage 2 week 3–4; placeholders seeded | Week 3 | Doc 20 DR-5; 28 DR-1 |
| P-10 | Shop-size mix data for rollout and till-count planning (H-06) | About 1.5 tills per shop | Before wave planning | Doc 16 H-06 |
| P-11 | Design and run the cutover balance-confirmation exercise (every opening balance signed by both parties) | Two-signature opening balance document designed (doc 25 §4.7); the exercise itself is doc 37 | Before any opening-balance migration | Baseline v0.2 §5; 25; 37 |
| P-12 | Obtain real SLCERT / TechCERT certification quotations | Cost model figures are placeholders | Before doc 34 sign-off | Baseline v0.2 §5 |
| P-13 | Sequence the penetration test before the certification audit | Pen test in doc 34's assurance plan | Doc 34 | Baseline v0.2 §5 |
| P-14 | Determine the source of the 6,000 new shops (existing MPCS expansion vs new societies) | Mix unknown; onboarding playbook (doc 37) handles both | Before wave planning | Baseline v0.2 §5 |
| P-15 | Schedule POS prototype-validation sessions with real cashiers | Spike 7, two rounds before the M6 contract freeze | Stage 2 weeks 4 and 6 | Baseline v0.2 §5; doc 14 §8; 26A T-13 |
| P-16 | IRD e-invoicing specification watch | Readiness seam only (doc 29 §3.5); nothing published as of last check | Monitor | Baseline v0.2 §3 |
| P-17 | Table the 13-item ratification batch as one sitting (see F-17) | Defaults stand; sitting not held | Before pilot | Baseline v0.2 §2 |

### 3.2 Federation board and admins

| Ref | Item | Assumed / proposed | Needed by | Source |
|---|---|---|---|---|
| F-01 | Hosting option: cloud with managed PostgreSQL, or local with cloud standby | Cloud with managed Postgres unless residency requires local | Pilot gate at the latest; earlier for doc 35 | Doc 14 DR-1 |
| F-02 | Support hours: extended plus on-call versus full 24×7 | Extended plus on-call | Before pilot | Doc 16 Q-04; baseline v0.2 §1 |
| F-03 | Direct Federation→MPCS trading flag | Present, default off | Week 2 | Doc 21 DR-3 |
| F-04 | Entity activation prerequisites | Officer named, admin user, VAT registration | Week 2 | Doc 21 DR-6 |
| F-05 | Returning staff: new user with succession link | New record | Week 2 | Doc 21 DR-5 |
| F-06 | Deactivated SKU sell-through | On: tills sell lots on hand | Week 3 | Doc 22 DR-3 |
| F-07 | Local image override for shared SKUs | Allowed | Week 3 | Doc 22 DR-5 |
| F-08 | Stacking policy; picker gap; advisory rule adoption | `PRIORITY_THEN_BEST`; Rs 20 or 5%; adopt by copy | Week 4 | Doc 23 DR-1, 4, 5 |
| F-09 | Invoice timing; GRN reversal window; escalation grace; backorder review age; claim window | Invoice follows GRN; 24 h; 3 days; 30 days; 14 days | Week 5 | Doc 24 DR-1, 3–6 |
| F-10 | Remote witness for single-staff shops; opening balance as activation gate | Adopt; yes | Week 6 | Doc 25 DR-4, DR-6 |
| F-11 | Card reference mandatory; parked sale age; keyed weight; customer display | Yes; 120 min; permitted with audit; where a second screen exists | Week 6 | Doc 26 DR-3, 5, 7, 8; 30 DR-4 |
| F-12 | Update rings, gate thresholds, grace period; quiet hour for 24-hour shops | 1% canary; 14 days; 02:00–04:00 | Before pilot | Doc 31 DR-4, DR-5 |
| F-13 | MPCS-authored notification rules; webhooks phase | Allowed within scope; phase 2 | Week 7 | Doc 29 DR-5, DR-6 |
| F-14 | RTO 4–8 h / RPO 15 min: accept as stated | Accepted as planning basis | Doc 36 | Baseline v0.2 §1; doc 14 §4 |
| F-16 | Minimum connectivity go-live gate values: accept as stated | Accepted; enforced at location activation | Before pilot | Baseline v0.2 §1; doc 21 §4.3 |
| F-17 | Ratify the 13 standing defaults (Section 2) in one sitting | Defaults stand unless objected to | Before pilot | Baseline v0.2 §2 |

(F-15 is not assigned in v0.4.)

### 3.3 Federation legal

| Ref | Item | Assumed / proposed | Needed by | Source |
|---|---|---|---|---|
| L-01 | Co-operative Act legal opinion on joint data ownership (M-01) | Joint controllers | Before pilot | Doc 16 M-01; baseline v0.2 §3 |
| L-02 | Data-governance / joint-controller agreement drafted and signed per entity (M-11, ADR-26) | Drafting parallel to build; `entity.data_governance_signed_on` | Before entity activation | Doc 16 M-11; 21 |
| L-03 | Government IT policy check: any binding state-cloud circular (Q-08) | No constraint identified | Before F-01 | Doc 14 DR-8; baseline v0.2 §3 |
| L-04 | SKU-scoped versus tag-scoped control price both in force | The lower applies | Week 4 | Doc 23 DR-2 |
| L-05 | Erasure wording and retention exemption for customer postings | Anonymise; retain postings 7 years | Week 5 | Doc 27 DR-4 |
| L-06 | NIC capture as hash plus last four when credit is granted | Adopt | Week 5 | Doc 27 DR-5 |
| L-07 | External grant maximum duration for regulator and auditors | 12 months | Week 2 | Doc 21 DR-4 |
| L-08 | PDPA substantive-parts commencement watch | Design already meets the Act's obligations (doc 27 §3.2); monitor gazetting | Monitor | Baseline v0.2 §3 |

### 3.4 Federation accounts and MPCS treasurers

| Ref | Item | Assumed / proposed | Needed by | Source |
|---|---|---|---|---|
| A-01 | Posting map account roles reviewed by an accountant; mapped document types | As seeded (24A, 25A, 27A) | Week 7 | Doc 24A §3; 29 DR-3 |
| A-02 | Journal export formats before the package is chosen | JSON and CSV with account roles | Week 7 | Doc 29 DR-2 |
| A-03 | Tier basis for volume pricing | Ordered quantity | Week 5 | Doc 24 DR-2 |
| A-04 | Trade price above lowest MRP | Review, not block | Week 4 | Doc 23 DR-3 |
| A-05 | Write-off value basis for approval bands | Entity average cost | Week 6 | Doc 25 DR-3 |
| A-06 | Offline khata cap; limit policy; repayment allocation; offline repayment capture | Rs 5,000; warn; oldest-first; allowed | Week 5 | Doc 27 DR-1–3; 20 DR-3 |
| A-07 | Banking handover step recorded separately from bank deposit | Yes | Week 5 | Doc 27 DR-7 |
| A-08 | Credit note from an accepted discrepancy proposal without seller MFA | MFA waived | Week 5 | Doc 24A DR-1 |
| A-09 | Cross-session refund series | Supervisor's till series at a till; location refund series on the web | Week 6 | Doc 26 DR-1 |

### 3.5 Engineering lead, platform pair, till track

| Ref | Item | Assumed / proposed | Needed by | Source |
|---|---|---|---|---|
| E-01 | Identity provider product | Keycloak-class self-hosted, behind `IdentityProviderClient` | Sprint 0 | Doc 19 DR-1 |
| E-02 | Message broker product | RabbitMQ quorum queues, behind `BrokerAdapter` | Sprint 0 | Doc 14 DR-2; 19 DR-2 |
| E-03 | Web component base library; wireframing toolchain | Headless primitives; design tool with mirrored library | Sprint 0 | Doc 30 DR-2, DR-3 |
| E-04 | Role template drift policy | Notify and diff | Week 2 | Doc 19 DR-4 |
| E-05 | PIN policy; MFA freshness; numerals in Sinhala and Tamil | 4–6 digits, 5 tries / 15 min; 12 h; Western digits | Week 2 (numerals after usability round 1) | Doc 19 DR-5–7; baseline v0.2 §1 |
| E-06 | Sync tunables: batch limits, chunk size, outbox and change-log retention, staleness, push | 500 / 2 MB; 50; 7 and 30 days; 3 days; none | After spikes 1 and 5 | Doc 32 DR-1–6 |
| E-07 | A4 rendering engine | Headless Chromium worker with Noto fonts | Sprint 0 | 19A DR-3 |
| E-08 | Non-primary tills and LOCATION-series documents | Drafts only | Week 6 | Doc 26 DR-2 |
| E-09 | Direct-drop GRN without snapshot data | Local-supply capture keyed against the DN number | Week 5 | Doc 24 DR-7 |
| E-10 | Count-while-trading expectation adjustment | Movements during the count adjust the expectation | Week 6 | Doc 25 DR-8 |
| E-11 | Snapshot churn coalescing window; thumbnail fetch policy; swap timing | 5 min; lazy over Wi-Fi; between sales | After spike 5 | 25A, 26A §13 |
| E-12 | Kiosk-exit procedure for the helpdesk | Supervisor PIN plus time-boxed helpdesk code | Doc 38 | Doc 26 DR-9 |
| E-13 | Repack recipes shareable via promote-to-shared (ratification default 5) | Not in v1; recipes entity-owned | Week 6 | Baseline v0.2 §2; doc 25 |

### 3.6 DevOps

| Ref | Item | Assumed / proposed | Needed by | Source |
|---|---|---|---|---|
| D-01 | Management agent product (validated in spike 6) | Open-source device-owner MDM | With hardware selection | Doc 31 DR-1; 14 DR-5 |
| D-02 | Archival and cold-storage tiering policy | Monthly partitions; detach after 24 months, never drop | Before pilot | Doc 14 DR-6 |
| D-03 | Columnar store timing | Phase 2, triggered by the end-of-day window | Phase 2 | Doc 14 DR-7; 19 DR-3 |
| D-04 | Observability stack product | Prometheus/Grafana/Loki class | Doc 36 | Doc 14 DR-9 |
| D-05 | Hash-chain anchor storage (phase 2) | Write-once object storage | Phase 2 | Doc 19 DR-8 |
| D-06 | Export caps and retention; freshness stale threshold; analyst role issuance | 500,000 rows / 7 days; 24 h; named analysts | Week 6 | Doc 28 DR-3–5 |

### 3.7 Procurement

| Ref | Item | Assumed / proposed | Needed by | Source |
|---|---|---|---|---|
| H-01 | Terminal model shortlist and final choice; scale integration is the gating test | Two or three candidates through spikes 2, 3, 6 | Stage 2 week 4 | Doc 14 DR-3 |
| H-02 | GMS-certified or AOSP terminals | On hardware merits; update path works for both | With H-01 | Doc 14 DR-4; 31 DR-2 |
| H-03 | Hosting quotes for the doc 14 matrix | August planning ranges | Before F-01 | Doc 14 §5 |
| H-04 | SMS providers (two, for failover) | Placeholders; provider-neutral adapter | Before pilot | Doc 29 DR-4 |
| H-05 | Gazette control-price operational owner (content responsibility) | Federation catalogue steward | Before pilot | Doc 18 DR-8 |

## 4. Parked by deliberate decision

Carried from the baseline register; each now has a home in doc 20 Appendix A (the deferred-capability register) with its cost of reversal.

| Item | Parked to | Design reference |
|---|---|---|
| Subsidy instruments / vouchers / coupons | Later tender phase, by federation decision | Doc 26 §3.3 (tender kinds reserved); doc 20 App. A |
| Federation→MPCS direct trading channel activation terms | Configuration action when the Federation opens the channel | Doc 21 §3.2 flag (F-03) |
| A-10 / A-12 full automation (credit blocking, interest calculation) | After accounting-system integration | Doc 24 §3.8 (warn only); §7 interest rule manual |
| Self-service report builder | Backlog | Doc 28 §1 |
| Mobile manager dashboards | Backlog (responsive web covers browser access) | Doc 28 §1; doc 30 |
| LAN peer stock-visibility gossip between tills | Phase 2; event schema already permits it | Doc 20 App. A (shop-hub option); doc 26 §3.11 |
| Federation as a repacking entity | Permission flip when the Federation activates it (L-06 answer) | Doc 25 §1 |

## 5. Pending external input

| Item | Owner | Status |
|---|---|---|
| Tax advisor's written answers (multi-series numbering, post-SVAT invoice content, FY reset, retention format, e-invoicing) | Tax advisor | Programme adopted the assumptions on 9 September 2026 (24B); the review is now advisory and any differing answer is a change request |
| Co-operative Act legal opinion on joint data ownership (M-01) | Federation legal counsel | Not yet commissioned (L-01) |
| Data-governance / joint-controller agreement (M-11, ADR-26) | Federation legal counsel | Not yet commissioned (L-02) |
| Government IT policy check: binding state-cloud circular (Q-08) | Federation legal counsel | Not yet commissioned (L-03) |
| PDPA substantive-parts commencement | Programme / legal | Monitoring (L-08) |
| IRD e-invoicing specification | Programme | Monitoring (P-16) |
| Numbering RFC stakeholder approval (ADR-11) | Federation stakeholders | Closed by 24B: multi-series adopted |

## 6. RFCs

**Document Numbering RFC** (ADR-11) — closed: the multi-series gapless design was adopted on 9 September 2026 (24B). The pre-allocated-range fallback is no longer under consideration.

**Ratification Defaults RFC** — the 13-item batch in Section 2; still to be tabled as one sitting (F-17 / P-17). Item 5 (recipe sharing) is the only default not yet embodied in a design document.

## 7. Closed since the baseline

| Ref | Item | Closed by | Outcome |
|---|---|---|---|
| C-01 | Financial-year reset; GRN fiscal scope; multi-series; voids; discrepancy/claim numbering; retention | 24B (9 Sep 2026) | No reset; GRN non-fiscal; adopted; voids keep numbers; non-fiscal; indefinite |
| C-02 | Hosting bound to AWS Mumbai | Doc 14 v0.2 | Provider-neutral; matrix |
| C-03 | Trading documents as their own module | Doc 17 v0.2.1 (ADR-37) | M4 |
| C-04 | Batch ownership | Doc 17 v0.2.1, doc 22 (ADR-38) | Identity in M2, holding in M5 |
| C-05 | Responsible officer fields; `sod_pair` | Doc 18 v0.2 | Applied |
| C-06 | Product images; attributes jsonb; analyst SQL role | Doc 20 v0.2, doc 18 v0.2 | Adopted for v1 |
| C-07 | Procurement missing from doc 20 | Doc 20 v0.3 | M10 boundary added |
| C-08 | Till technology stack | Doc 30 DR-1, doc 14 (ADR-50) | Native Android |
| C-09 | Multi-MRP resolution and control-price table | Multi-MRP study, doc 23 (ADR-41) | `AUTO_LOWEST` default; hard block |
| C-10 | Minimal supplier table; assortment; `has_printed_mrp`; label content | Doc 22, doc 18 v0.3 | Adopted |
| C-11 | M3, M4, M5, M7, M8, M9 table change requests | Doc 18 v0.3 | Applied |
| C-12 | `payer_kind` CUSTOMER on M4 payment receipts | Doc 27 (CR-27-2), doc 18 v0.3 | Withdrawn; CPR is M7 |
| C-13 | Damaged lots; oversell handling; availability definition | Doc 25 (ADR-44, 45) | Adopted as proposed |
| C-14 | Single-device shops; shop-management mode | Doc 17 ADR-36; doc 26 | Adopted |
| C-15 | Cross-cutting services renamed Kernel Services; kernel index | Doc 19 v0.2 | Done |
| C-16 | Doc 33 as a build artefact | Doc 29A §7 | Bundle assembler |
| C-17 | Sync retention on the till after ack | Doc 32 S8 | 7 days |
| C-18 | Google developer verification impact on update path | Doc 31 §3 | Device-owner agent path on GMS or AOSP |
| C-19 | 10,000-shop / 3-year growth envelope | ADR-22; doc 14 §6 | Adopted as planning basis |
| C-20 | Till authentication PIN vs password | Doc 19 §2; doc 26 | PIN with Argon2id; parameters in E-05 |
| C-21 | Repository and host | 17 Sep 2026 | github.com/Knoweb/COOP_ERP; GitHub Actions |

*Versioning convention: the published Word file is never edited in place on Drive; a superseded version is replaced by a new versioned file and the old one is moved to trash. This Markdown file is the source; edit it, bump the version line, render the Word copy, and record the new version in the register.*
