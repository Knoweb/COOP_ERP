# DECISIONS_PENDING.md — assumptions in force

Mirror of doc 10 Open Items Register v0.3 (10 September 2026), kept in the repository so code can cite item ids. Doc 10 (Word, in the design folder) is the master; update this file when it changes. Rule: an open item never blocks build — build to the assumption, cite the id in a comment, never decide silently.

## Programme (Chamin)
| Id | Item | Assume |
|---|---|---|
| P-01 | Catalogue data steward and cleansing owner | Unnamed; M2 steward tooling built for them |
| P-02 | Twelve-month success measures | Not defined |
| P-03 | Team allocation (platform pair, module developers, till track) | Tentative; fresh graduates and trainees; Sprint 0 runs about three weeks |
| P-04 | Stage 2 pace re-plan | Doc 20 v0.3 dates are unmeasured estimates |
| P-05 | Tax advisor review | Advisory only; 24B decisions stand |
| P-06 | Receipt legal content | Doc 26 §3.4 |
| P-07 | NFR-UPD-040 LAN-shared updates | Deferred; management-agent path |
| P-08 | Register Federation as verified Android developer | To do |
| P-09 | Report catalogue workshop | Placeholders seeded (28A) |
| P-10 | Shop-size mix | About 1.5 tills per shop |

## Federation board and admins
| Id | Item | Assume |
|---|---|---|
| F-01 | Hosting | Cloud with managed PostgreSQL (DigitalOcean-class) unless residency requires local |
| F-02 | Support hours | Extended plus on-call |
| F-03 | Direct Federation→MPCS trading | Config flag present, default off |
| F-04 | Entity activation prerequisites | Officer named, admin user, VAT registration |
| F-05 | Returning staff | New user record with `succeeds_user_id` |
| F-06 | Deactivated SKU sell-through | On |
| F-07 | Local image override for shared SKUs | Allowed |
| F-08 | Stacking policy; picker gap; advisory rules | PRIORITY_THEN_BEST; Rs 20 or 5%; adopt by copy |
| F-09 | Invoice timing; GRN reversal; escalation grace; backorder age; claim window | Follows GRN; 24 h; 3 days; 30 days; 14 days |
| F-10 | Remote witness; opening balance gate | Adopt; yes |
| F-11 | Card reference; parked sale age; keyed weight; customer display | Mandatory; 120 min; permitted with audit; where a second screen exists |
| F-12 | Update rings, grace, quiet hour | 1% canary; 14 days; 02:00–04:00 |
| F-13 | MPCS-authored notification rules; webhooks | Allowed within scope; phase 2 |

## Federation legal
| Id | Item | Assume |
|---|---|---|
| L-01 | Co-operative Act opinion on data ownership | Joint controllers |
| L-02 | Data-governance agreement per entity | Drafted in parallel; `entity.data_governance_signed_on` |
| L-03 | Data residency policy check | No constraint |
| L-04 | SKU vs tag control price both in force | The lower applies |
| L-05 | Erasure wording and retention | Anonymise; retain postings 7 years |
| L-06 | NIC capture | Hash plus last four, only when credit granted |
| L-07 | External grant maximum | 12 months |

## Federation accounts and MPCS treasurers
| Id | Item | Assume |
|---|---|---|
| A-01 | Posting map account roles; mapped document types | As seeded in 24A, 25A, 27A |
| A-02 | Journal export formats | JSON and CSV with account roles |
| A-03 | Volume tier basis | Ordered quantity |
| A-04 | Trade price above lowest MRP | Review, not block |
| A-05 | Write-off value basis | Entity average cost |
| A-06 | Offline khata cap; limit policy; allocation; offline repayment | Rs 5,000; warn; oldest-first; allowed |
| A-07 | Banking handover step | Recorded separately from bank deposit |
| A-08 | Credit note from accepted discrepancy proposal | Seller MFA waived |
| A-09 | Cross-session refund series | Supervisor's till series at a till; location refund series on the web |

## Engineering lead, platform pair, till track
| Id | Item | Assume |
|---|---|---|
| E-01 | Identity provider | Keycloak-class, behind `IdentityProviderClient` |
| E-02 | Message broker | RabbitMQ quorum queues, behind `BrokerAdapter` |
| E-03 | Web component base; wireframing tool | Headless primitives; design tool with mirrored library |
| E-04 | Role template drift | Notify and diff |
| E-05 | PIN policy; MFA freshness; numerals | 4–6 digits, 5 tries / 15 min lockout; 12 h; Western digits in all languages |
| E-06 | Sync tunables | 500 events / 2 MB per batch; 50 per chunk; 7-day outbox retention after ack; 30-day change log; 3-day staleness; no push |
| E-07 | A4 rendering engine | Headless Chromium worker with bundled Noto fonts |
| E-08 | Non-primary tills and LOCATION-series documents | Drafts only |
| E-09 | Direct-drop GRN without snapshot data | Local-supply capture keyed against the DN number |
| E-10 | Count-while-trading | Movements during the count adjust the expectation |
| E-11 | Snapshot churn window; thumbnails; swap timing | 5 min coalesce; lazy over Wi-Fi; between sales |
| E-12 | Kiosk-exit procedure | Supervisor PIN plus time-boxed helpdesk code |

## DevOps
| Id | Item | Assume |
|---|---|---|
| D-01 | Management agent (MDM) | Open-source device-owner agent, validated in spike 6 |
| D-02 | Archival tiering | Monthly partitions; detach after 24 months, never drop |
| D-03 | Columnar store | Phase 2, triggered by end-of-day window |
| D-04 | Observability stack | Prometheus/Grafana/Loki class |
| D-05 | Hash-chain anchor storage | Phase 2; write-once object storage |
| D-06 | Export caps; freshness threshold; analyst issuance | 500,000 rows / 7 days; 24 h; named analysts |

## Procurement
| Id | Item | Assume |
|---|---|---|
| H-01 | Terminal model | Generic 8-inch 1280×800 profile until chosen; scale integration is the gating test |
| H-02 | GMS or AOSP terminals | On hardware merits; update path works for both |
| H-03 | Hosting quotes | August planning ranges |
| H-04 | SMS providers | Two, provider-neutral HTTP adapter |
| H-05 | Gazette control-price content owner | Federation catalogue steward |

## Closed (for reference)
Numbering N-1…N-6 (24B); provider-neutral hosting (14 v0.2); M4 as its own module (ADR-37); batch identity in M2, lot in M5 (ADR-38); responsible officer and `sod_pair` (18 v0.2); images, attributes, analyst role (18/20); M10 boundary (20 v0.3); native Android till (ADR-50); multi-MRP and control prices (ADR-41); supplier, assortment, `has_printed_mrp`, label content (22, ADR-43); all module table CRs (18 v0.3); CPR as M7 (CR-27-2); damaged lots, oversell, availability (ADR-44, 45); shop-management mode (ADR-36); kernel rename (19 v0.2); doc 33 as build artefact; 7-day till retention after ack (32); developer-verification path (31).
