English | [中文版](./CHANGELOG.cn.md)

# Changelog — Java SDK (`nps-java`)

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Until NPS reaches v1.0 stable, every repository in the suite is synchronized to the same pre-release version tag.

---

## [1.0.0-alpha.14] — Unreleased

### Added
- `com.labacacia.nps.nip.NipCaClient`: typed remote NIP CA client for discovery, CRL, agent/node registration, X.509 registration, renewal, revocation, and verification.
- `com.labacacia.nps.nwp.NwpNativeNodeServer`: native-mode NWP serving helper for dispatching QueryFrame/ActionFrame over an already established NCP stream.
- `com.labacacia.nps.conformance`: TC-N1/TC-N2 conformance catalog, manifest builder, and validator for CI/self-certification flows.

---

## [1.0.0-alpha.11] — 2026-05-28

### Added

#### NOP — saga compensation & stream flow control (alpha.9 + alpha.11)

- `DagNode` — new class representing a typed DAG node entry; carries optional `compensateAction` (String) and `compensateParamsMapping` (Map<String,Object>) fields for saga compensation.
- `TaskFrame` — new field `compensationPolicy` (String, default `"none"`); accepted values defined in new `CompensationPolicy` constants class.
- `CompensationPolicy` — new constants class: `NONE`, `ON_FAILURE`, `ALWAYS`.
- `TaskState` enum — new values `COMPENSATING` and `COMPENSATED`.
- `AggregateStrategy` — new constants class with `MERGE`, `FIRST`, `WEIGHTED_FIRST_K`, `MERGE_ALL`.
- `DelegateFrame` — new optional field `targetClusterAnchor` (String, JSON key `target_cluster_anchor`).
- `AlignStreamFrame` — new optional fields `ackSeq` (Long, JSON key `ack_seq`) and `nakSeq` (Long, JSON key `nak_seq`).

#### NDP — security profiles & §5 GraphFrame (alpha.9 + alpha.11)

- `SecurityProfile` — new constants class: `LOCAL_DEV`, `ORG_PRIVATE`, `PUBLIC_FEDERATED`.
- `InMemoryNdpRegistry` — new `securityProfile` field (default `"local-dev"`); new `checkSecurityProfile(AnnounceFrame)` method enforcing RFC-1918/loopback addresses under `org-private`; ephemeral TTL cap of 60 s applied when profile is `org-private`.
- `AnnounceFrame` — new optional fields: `nodeRoles` (List<String>), `clusterAnchor` (String), `spawnSpecRef` (String), `bridgeProtocols` (List<String>), `activationMode` (String), `activationEndpoint` (String).
- `GraphNode` — new class with fields `nid` (String), `clusterAnchor` (String, optional), `nodeRoles` (List<String>, optional).
- `GraphEdge` — new class with fields `fromNid` (String), `toNid` (String), `latencyMs` (Integer, optional), `protocol` (String, optional).
- `GraphFrame` — rewritten to §5 format: fields are now `graphId` (String), `nodes` (List<GraphNode>), `edges` (List<GraphEdge>), `ttl` (int), `metadata` (Map<String,Object>, optional). The previous seq/initial_sync/patch layout is superseded.

#### NIP — reputation policy & OCSP staple (alpha.10 + alpha.11)

- `IdentReputationPolicyHint` — new class with fields `logSources` (List<String>) and `consent` (boolean).
- `IdentMetadata` — new typed wrapper for the `IdentFrame` metadata map; carries optional `reputationPolicy` (IdentReputationPolicyHint).
- `IdentFrame` — new optional field `ocspStaple` (String, JSON key `ocsp_staple`); new full constructor accepting all fields including the staple.

#### NWP — subscribe frame (alpha.11)

- `SubscribeFrame` — new frame class (frame type `0x12`) with fields `subscriptionId` (String), `filter` (Map<String,Object>, optional), `heartbeatIntervalMs` (Integer, optional), `maxEvents` (Integer, optional), `cursor` (String, optional).
- `FrameType` — new entry `SUBSCRIBE (0x12)`.
- `NwpFrameRegistrar` — registers `SubscribeFrame`.

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

[1.0.0-alpha.2]: https://github.com/LabAcacia/nps/releases/tag/v1.0.0-alpha.2
[1.0.0-alpha.1]: https://github.com/LabAcacia/nps/releases/tag/v1.0.0-alpha.1
