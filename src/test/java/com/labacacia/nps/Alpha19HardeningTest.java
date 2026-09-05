// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labacacia.nps.alpha19.Alpha19Policies;
import com.labacacia.nps.alpha19.Alpha19Policies.Family;
import java.nio.file.Files;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

final class Alpha19HardeningTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void executesAllSharedVectors() throws Exception {
    var suites =
        new Object[][] {
          {"ncp", "runtime_hardening_vectors.json"},
          {"nwp", "alpha19_hardening_vectors.json"},
          {"nip", "renewal_revocation_vectors.json"},
          {"ndp", "recovery_fence_vectors.json"},
          {"nop", "replay_retention_vectors.json"}
        };
    var seen = new HashSet<String>();
    for (var suite : suites) {
      var root =
          mapper.readTree(
              Files.readAllBytes(ConformanceFixtures.resolve(suite[0] + "/" + suite[1])));
      for (var vector : root.path("vectors")) {
        String id = vector.path("id").asText();
        assertTrue(seen.add(id), id);
        assertEquals(
            vector.path("expected").toString(),
            Alpha19Policies.evaluate(family(id), (ObjectNode) vector.path("input")).toString(),
            id);
      }
    }
    assertEquals(47, seen.size());
  }

  @Test
  void boundaryBranchesAreNotFixtureConstants() {
    assertEquals(
        2500,
        Alpha19Policies.evaluate(
                Family.NCP,
                (ObjectNode)
                    mapper.createObjectNode().put("client_ping_ms", 0).put("server_ping_ms", 2500))
            .path("effective_interval_ms")
            .asInt());
  }

  private static Family family(String id) {
    if (id.startsWith("ncp.")) return Family.NCP;
    if (id.contains(".metadata.")) return Family.NWP_METADATA;
    if (id.contains(".subscription.")) return Family.NWP_SUBSCRIPTION;
    if (id.contains(".renewal.")) return Family.NIP_RENEWAL;
    if (id.contains(".revocation.")) return Family.NIP_REVOCATION;
    if (id.contains(".advisory.")) return Family.NIP_ADVISORY;
    if (id.startsWith("ndp.")) return Family.NDP;
    return Family.NOP;
  }
}
