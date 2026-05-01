English | [中文版](./CHANGELOG.cn.md)

# Changelog — Java SDK (`nps-java`)

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until NPS reaches v1.0 stable, every repository in the suite is synchronized to the same pre-release version tag.

---

## [1.0.0-alpha.5] — 2026-05-01

### Added

- **`NwpErrorCodes` class** — new `com.labacacia.nps.nwp.NwpErrorCodes` with all 30 NWP wire error codes (auth, query, action, task, subscribe, infrastructure, manifest, topology, reserved-type). Missing from previous releases.
- **`NDP.resolveViaDns` — DNS TXT fallback resolution** — new `InMemoryNdpRegistry.resolveViaDns(target, dnsLookup?)` falls back to `_nps-node.{host}` TXT record lookup (NPS-4 §5) when no in-memory entry matches. New `DnsTxtLookup` functional interface, `SystemDnsTxtLookup` (JNDI `DnsContextFactory`), and `NpsDnsTxt` parse helpers. Tests: 112 → 122.

### Changed

- **`AssuranceLevel.fromWire("")` returns `ANONYMOUS`** — `if (wire == null)` changed to `if (wire == null || wire.isEmpty())` so `""` returns `ANONYMOUS` (spec §5.1.1 backward-compat fix).
- **Version bump to `1.0.0-alpha.5`** — synchronized with NPS suite alpha.5 release.

### Fixed

- **`NipErrorCodes.REPUTATION_GOSSIP_FORK` / `REPUTATION_GOSSIP_SIG_INVALID`** — two new NIP reputation gossip error codes added (RFC-0004 Phase 3).

---

## [1.0.0-alpha.4] — 2026-04-30

### Added

- **NPS-RFC-0001 Phase 2 — NCP connection preamble (Java helper
  parity).** `com.labacacia.nps.ncp.NcpPreamble` exposes
  `writePreamble(OutputStream)` and `readPreamble(InputStream)`
  round-tripping the literal `b"NPS/1.0\n"` sentinel; covered by
  `NcpPreambleTests`. Brings Java in line with the .NET / Python /
  TypeScript / Go preamble helpers shipped at alpha.4.
- **NPS-RFC-0002 Phase A/B — X.509 NID certificates + ACME `agent-01`
  (Java port).** New surface under `com.labacacia.nps.nip`:
  - `nip.x509` — X.509 NID certificate builder + verifier (built on
    Bouncy Castle).
  - `nip.acme` — ACME `agent-01` client + server reference (challenge
    issuance, key authorisation, JWS-signed wire envelope per
    NPS-RFC-0002 Phase B).
  - `nip.AssuranceLevel` — agent identity assurance levels
    (`anonymous` / `attested` / `verified`) per NPS-RFC-0003.
  - `nip.IdentCertFormat` — IdentFrame `cert_format` discriminator
    (`v1` Ed25519 vs. `x509`).
  - `nip.NipErrorCodes` — NIP error code namespace.
  - `nip.NipIdentVerifier` + `NipIdentVerifyResult` +
    `NipVerifierOptions` — dual-trust IdentFrame verifier
    (v1 + X.509).
  - `nip.NipCanonicalJson` — canonical JSON helper used by the
    verifier and X.509 builder.
- New tests: `NcpPreambleTests`, `NipX509Tests`, `AcmeAgent01Tests`.
  Total: 112 tests green (was 87 at alpha.3).

### Changed

- Maven coordinate `com.labacacia.nps:nps-java:1.0.0-alpha.4`.
- `nip.IdentFrame` extended with optional `cert_format` discriminator
  + `x509_chain` field alongside the existing v1 Ed25519 fields.
  v1 IdentFrames written by alpha.3 consumers continue to verify
  unchanged.

### Suite-wide highlights at alpha.4

- **NPS-RFC-0002 X.509 + ACME** — full cross-SDK port wave (.NET /
  Java / Python / TypeScript / Go / Rust). Servers can now issue
  dual-trust IdentFrames (v1 Ed25519 + X.509 leaf cert chained to a
  self-signed root) and self-onboard NIDs over ACME's `agent-01`
  challenge type.
- **NPS-CR-0002 — Anchor Node topology queries** — `topology.snapshot`
  / `topology.stream` query types (.NET reference + L2 conformance
  suite). Java consumer-side helpers planned for a later release.
- **`nps-registry` SQLite-backed real registry** + **`nps-ledger`
  Phase 2** (RFC 9162 Merkle + STH + inclusion proofs) shipped in the
  daemon repos.

---

## [1.0.0-alpha.3] — 2026-04-25

### Changed

- Version bump to `1.0.0-alpha.3` for suite-wide synchronization with the NPS `v1.0.0-alpha.3` release. No functional changes in the Java SDK at this milestone.
- 87 tests still green.

### Suite-wide highlights at alpha.3 (per-language helpers planned for alpha.4)

- **NPS-RFC-0001 — NCP connection preamble** (Accepted). Native-mode connections now begin with the literal `b"NPS/1.0\n"` (8 bytes). Reference helper landed in the .NET SDK; Java helper deferred to alpha.4.
- **NPS-RFC-0003 — Agent identity assurance levels** (Accepted). NIP IdentFrame and NWM gain a tri-state `assurance_level` (`anonymous`/`attested`/`verified`). Reference types landed in .NET; Java parity deferred to alpha.4.
- **NPS-RFC-0004 — NID reputation log (CT-style)** (Accepted). Append-only Merkle log entry shape published; reference signer landed in .NET (and shipped as the `nps-ledger` daemon Phase 1). Java helpers deferred to alpha.4.
- **NPS-CR-0001 — Anchor / Bridge node split.** The legacy "Gateway Node" role is renamed to **Anchor Node**; the "translate NPS↔external protocol" role is now its own **Bridge Node** type. AnnounceFrame gained `node_kind` / `cluster_anchor` / `bridge_protocols`. Source-of-truth changes are in `spec/` + the .NET reference implementation.
- **6 NPS resident daemons.** New `daemons/` tree in NPS-Dev defines `npsd` / `nps-runner` / `nps-gateway` / `nps-registry` / `nps-cloud-ca` / `nps-ledger`; `npsd` ships an L1-functional reference and the rest ship as Phase 1 skeletons.

### Covered modules

- com.labacacia.nps.{core,ncp,nwp,nip,ndp,nop}

---

## [1.0.0-alpha.2] — 2026-04-19

### Changed

- Version bump to `1.0.0-alpha.2` for suite-wide synchronization. No functional changes beyond version alignment.
- 87 tests green.

### Covered modules

- com.labacacia.nps.{core,ncp,nwp,nip,ndp,nop}

---

## [1.0.0-alpha.1] — 2026-04-10

First public alpha as part of the NPS suite `v1.0.0-alpha.1` release.

[1.0.0-alpha.5]: https://github.com/labacacia/NPS-sdk-java/releases/tag/v1.0.0-alpha.5
[1.0.0-alpha.4]: https://github.com/labacacia/NPS-sdk-java/releases/tag/v1.0.0-alpha.4
[1.0.0-alpha.3]: https://github.com/LabAcacia/NPS-Dev/releases/tag/v1.0.0-alpha.3
[1.0.0-alpha.2]: https://github.com/LabAcacia/NPS-Dev/releases/tag/v1.0.0-alpha.2
[1.0.0-alpha.1]: https://github.com/LabAcacia/NPS-Dev/releases/tag/v1.0.0-alpha.1
