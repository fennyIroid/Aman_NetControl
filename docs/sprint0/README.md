# Aman — Sprint 0 Technical Deliverables

Prepared by iRoid Solutions. Covers the Android engineering portion of the Sprint 0 scope.

## Documents

| # | Deliverable | Document | Status |
|---|---|---|---|
| 3a | POC 1 — Values-based Blur | [POC1-Values-Based-Blur.md](POC1-Values-Based-Blur.md) | Engineering complete, device measurement pending |
| 3b | POC 2 — Core Protection and Anti-Bypass | [POC2-Protection-and-Anti-Bypass.md](POC2-Protection-and-Anti-Bypass.md) | Engineering complete, device validation pending |
| 4 | Android vs iOS Scope Notes | [Android-vs-iOS-Scope-Notes.md](Android-vs-iOS-Scope-Notes.md) | Complete |
| 5 | Store Policy / Entitlement Readiness | [Store-Policy-and-Entitlement-Readiness.md](Store-Policy-and-Entitlement-Readiness.md) | Complete |
| — | Filtering architecture: DNS vs VpnService | [Architecture-DNS-vs-VPN.md](Architecture-DNS-vs-VPN.md) | Complete — added at client request |
| — | **Harmful website blocking: response to the PO review** | [Website-Blocking-Coverage.md](Website-Blocking-Coverage.md) | Complete — measured resolver coverage, incl. Arabic; IP and relay bypasses closed |
| — | **Device test protocol** | [TEST-PROTOCOL.md](TEST-PROTOCOL.md) | Ready to run — fills the empty tables in 3a and 3b |
| — | **Test scenarios and fixtures** | [TEST-SCENARIOS.md](TEST-SCENARIOS.md) | What to point the protocol at |

Deliverables 1 (User Journey Summary), 2 (Arabic-First Prototype) and 6 (Final Scope Summary) are
design and joint deliverables and are not covered here.

## POC verdicts

| POC | Verdict | Gating item |
|---|---|---|
| **POC 1 — Values-based Blur** | **Go with limitation** | Requires a purpose-trained content classifier before any safety claim. Android only; not deliverable inside third-party apps on iOS. |
| **POC 2 — Protection and Anti-Bypass** | **Go** | Requires a decision on Play Store install vs Device Owner provisioning. |

## Decisions needed at the alignment meeting

1. **Is iOS in Phase 1?** Gates the Family Controls entitlement request, which is the longest
   external dependency in the project.
2. **Play Store install or Device Owner provisioning?** Determines how much of the anti-bypass story
   is prevention rather than detection, and changes the Android setup journey in the Figma.
3. **Adopt the hybrid filtering posture?** Recommended. Adds a resolver to the backend scope and a
   Private DNS step to the setup flow.
4. **Defer NotificationListenerService from the first submission?** Recommended.
5. **Is gender targeting in Values-based Blur a headline feature or a labelled best-effort option?**
   Recommended: best-effort. A headline claim needs its own model evaluation with a
   MENA-representative test set.

## What is not in these documents

Per the Sprint 0 scope, and stated so nothing is assumed:

- **No fabricated measurements.** Every table requiring device data is left empty and marked
  pending. Figures must come from runs that can be reproduced.
- **No exhaustive OEM testing.** Representative high-risk scenarios only; broader compatibility
  belongs to the full development and QA phase.
- **No production hardening** beyond what the POCs required.
- **No backend, admin panel, subscription or payment work** — explicitly outside Sprint 0.

## Where the implementation lives

The validation work is in code rather than only in prose, so the claims made to a parent on screen
and the claims validated here are generated from the same source.

| Area | Location |
|---|---|
| Bypass scenario catalogue (35 scenarios) | `platform/validation/BypassScenario.kt` |
| Filtering architecture and capability matrix | `platform/network/FilteringMode.kt` |
| Encrypted DNS defences | `platform/network/EncryptedDnsPolicy.kt` |
| Internet Pause | `platform/network/InternetPauseController.kt` |
| Allow-list system exemptions | `platform/network/SystemTrafficExemptions.kt` |
| Advanced Protection Mode detection | `platform/admin/AdvancedProtectionProbe.kt` |
| Banking app exclusions | `platform/network/TunnelExclusions.kt` |
| Private DNS detection | `platform/network/PrivateDnsGuard.kt` |
| Protection health model | `core/protection/ProtectionHealth.kt` |
| Pluggable content classifier | `platform/content/ContentSafetyModel.kt` |
| Subject detection and gender slot | `platform/content/SubjectDetector.kt` |
| Blur exposure measurement | `platform/content/BlurLatency.kt` |

273 unit tests cover the pure logic, including invariants that prevent the catalogue overclaiming
and the fail-safe rules in subject targeting.
