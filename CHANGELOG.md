English | [中文版](./CHANGELOG.cn.md)

# Changelog — Java SDK (`nps-java`)

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until NPS reaches v1.0 stable, every repository in the suite is synchronized to the same pre-release version tag.

---

## [1.0.0-alpha.12] — 2026-06-03

### Added

- **NCP v0.8 — `NopFrame` (0x07)**: `FrameType.NOP`; new `NopFrame` class; `HelloFrame.pingIntervalMs` (`int`, default `0`); `NCP_KEEPALIVE_TIMEOUT` / `NCP_REKEY_REQUIRED` error codes.
- **NWP v0.14 — manifest versioning**: `NwpHeaders.X_NWM_VERSION = "X-NWM-Version"` constant.
- **NIP v0.10 — `node_roles`**: `IdentFrame.nodeRoles` (`List<String>?`); `CERT_NODE_ROLES_MISMATCH` error code.
- **NDP v0.9 — heartbeat**: `AnnounceFrame.heartbeatIntervalMs` (`int`, default `60000`); `NDP_ANNOUNCE_STALE` error code.
- **NOP v0.7 — result TTL**: `TaskFrame.resultTtlSeconds` (`int`, default `3600`); `NOP_TASK_RESULT_EXPIRED` / `NOP_STREAM_NAK_UNRESOLVABLE` error codes.

### Tracking the suite

This release tracks NPS suite `v1.0.0-alpha.12`. NCP v0.8 / NWP v0.14 / NIP v0.10 / NDP v0.9 / NOP v0.7.

---

## [1.0.0-alpha.11] — 2026-05-31

### Added

- **NWP — `SubscribeFrame` CR-0006** (Breaking rewrite): Wire format updated — `subscriptionId` (required), `filter` (`Map<String,Object>?`), `heartbeatIntervalMs` (`Integer?`), `maxEvents` (`Integer?`), `cursor` (`String?`). **Wire breaking change vs alpha.8–10.**
- **NOP — AlignStream ack/NAK**: `AlignStreamFrame` gains `ackSeq` and `nakSeq` (`Long?`) for NOP v0.6 sliding-window acknowledgement.
- **NOP — Saga compensation**: `TaskFrame.compensationPolicy`; `DelegateFrame.targetClusterAnchor`; `AggregateStrategy.WEIGHTED_FIRST_K` / `MERGE_ALL`.
- **NDP — `GraphFrame` §5** (Breaking rewrite): New `GraphNode`, `GraphEdge` types; `GraphFrame` with `graphId`, `List<GraphNode>`, `List<GraphEdge>`, `ttl`, `metadata`. Max 256 nodes / 1024 edges.
- **NDP — `SecurityProfile`**: `LOCAL_DEV` / `ORG_PRIVATE` / `PUBLIC_FEDERATED` string constants; `InMemoryNdpRegistry.checkSecurityProfile()`.
- **NIP — `IdentFrame.ocspStaple`**: base64url DER OCSP response field (`String?`); `IdentReputationPolicyHint` / `IdentMetadata` types.

### Tracking the suite

This release tracks NPS suite `v1.0.0-alpha.11`. NCP v0.7 / NWP v0.13 / NIP v0.9 / NDP v0.8 / NOP v0.6.

---

## [1.0.0-alpha.10] — 2026-05-28

### Added

- **NOP — Saga compensation**: `DagNode` with `compensateAction` / `compensateParamsMapping`; `TaskState.COMPENSATING` / `COMPENSATED`; `CompensationPolicy.NONE` / `ON_FAILURE` / `ALWAYS`.
- **NDP — `SecurityProfile`**: `LOCAL_DEV` / `ORG_PRIVATE` / `PUBLIC_FEDERATED` string constants.
- **NIP — `IdentReputationPolicyHint`**: Reputation policy hint type; `IdentMetadata` typed wrapper.

### Tracking the suite

This release tracks NPS suite `v1.0.0-alpha.10`.

---

## [1.0.0-alpha.9] — 2026-05-28

### Added

- **NWP — `SubscribeFrame` (0x12)**: Initial `SubscribeFrame` (pre-CR-0006 format — replaced in alpha.11).
- **NWP — `ReputationPolicy` / `RepOutcome`**: RFC-0005 reputation types.

### Tracking the suite

This release tracks NPS suite `v1.0.0-alpha.9`.

---

## [1.0.0-alpha.8] — 2026-05-28

### Tracking the suite

This release tracks NPS suite `v1.0.0-alpha.8`.

Suite highlights: RFC-0005 `ReputationPolicyEvaluator` in .NET SDK; cgn_limit
pre-execution enforcement; RFC-0002 and RFC-0005 promoted to Accepted.

---

## [1.0.0-alpha.7] — 2026-05-17

### Added

- **`com.labacacia.nps.nip.reputation` package — `ReputationLogClient` (NPS-RFC-0004 Phase 2)**: Full HTTP client for the reputation-log operator API. `submit`, `query`, `getSTH`, `getProof`, `getGossipSTH`. `verifyInclusion` performs RFC 9162 §2.1.3.2 Merkle audit-path verification locally. Wire types: `ReputationLogEntry` (Builder pattern), `SignedTreeHead`, `InclusionProof`, `ObservationWindow`, `IncidentType` (8-value enum), `Severity` (5-value ordered enum). `ReputationLogException` carries `nipErrorCode` + `npsStatus`. `NipErrorCodes` gains `REPUTATION_GOSSIP_FORK` and `REPUTATION_GOSSIP_SIG_INVALID`. 30 regression tests.

- **`com.labacacia.nps.nwp.AnchorNodeClient` (NPS-CR-0002)**: HTTP client for Anchor Node topology queries using Java 11 `HttpClient`. `getSnapshot` returns `TopologySnapshot`. `subscribe` returns an `Iterator<TopologyEvent>` backed by NDJSON streaming. Typed events via `TopologyEvent` sealed-ish hierarchy. `AnchorTopologyException` for protocol errors. **Bug fix**: `subscribe()` iterator `hasNext()` order corrected — `pending != null` checked before `done` to prevent spurious `NoSuchElementException` on `ResyncRequiredEvent`. 25 regression tests.

### Tracking the suite

This release tracks NPS suite `v1.0.0-alpha.7`.

---

## [1.0.0-alpha.6] — 2026-05-14

### Changed

- **`NpsX509Oids` — IANA PEN 65715 (Breaking, CR-0004)**: `LAB_ACACIA_PEN_ARC` updated from `1.3.6.1.4.1.99999` to `1.3.6.1.4.1.65715`. New `ID_NPS_NODE_ROLES` (`EXTENSION_ARC + ".2"`) added and reserved per CR-0004. Certificates issued under the provisional arc must be revoked and re-issued.

- **`NipErrorCodes` — `REPUTATION_GOSSIP_FORK` / `REPUTATION_GOSSIP_SIG_INVALID` removed**: These two constants were premature Phase 3 additions; removed pending the RFC-0004 Phase 3 full specification.

- **`AssuranceLevel.fromWire("")` now throws (Breaking)**: `fromWire` is now strict — only `null` returns `ANONYMOUS`; empty string `""` throws `IllegalArgumentException`. Use `fromWireOrAnonymous` for null/empty-tolerant parsing.

- **Version bump to `1.0.0-alpha.6`** — synchronized with NPS suite alpha.6 release.

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

[1.0.0-alpha.8]: https://github.com/labacacia/NPS-sdk-java/releases/tag/v1.0.0-alpha.8
[1.0.0-alpha.7]: https://github.com/labacacia/NPS-sdk-java/releases/tag/v1.0.0-alpha.7
[1.0.0-alpha.2]: https://github.com/LabAcacia/nps/releases/tag/v1.0.0-alpha.2
[1.0.0-alpha.1]: https://github.com/LabAcacia/nps/releases/tag/v1.0.0-alpha.1
