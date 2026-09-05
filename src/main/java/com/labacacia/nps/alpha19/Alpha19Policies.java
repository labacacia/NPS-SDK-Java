// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.alpha19;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Side-effect-free alpha.19 protocol policies usable by runtimes and tests. */
public final class Alpha19Policies {
  public enum Family {
    NCP,
    NWP_METADATA,
    NWP_SUBSCRIPTION,
    NIP_RENEWAL,
    NIP_REVOCATION,
    NIP_ADVISORY,
    NDP,
    NOP
  }

  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern PRICE = Pattern.compile("^[A-Z]{3} [0-9]+(?:\\.[0-9]+)?$");

  private Alpha19Policies() {}

  public static ObjectNode evaluate(Family family, ObjectNode input) {
    return switch (family) {
      case NCP -> ncp(input);
      case NWP_METADATA -> nwpMetadata(input);
      case NWP_SUBSCRIPTION -> nwpSubscription(input);
      case NIP_RENEWAL -> nipRenewal(input);
      case NIP_REVOCATION -> nipRevocation(input);
      case NIP_ADVISORY -> nipAdvisory(input);
      case NDP -> ndp(input);
      case NOP -> nop(input);
    };
  }

  private static ObjectNode ncp(ObjectNode i) {
    if (i.has("client_ping_ms")) {
      int c = i.path("client_ping_ms").asInt(), s = i.path("server_ping_ms").asInt();
      List<Integer> offers = new ArrayList<>();
      if (c > 0) offers.add(c);
      if (s > 0) offers.add(s);
      var o = out().put("keepalive_enabled", !offers.isEmpty());
      if (offers.isEmpty()) o.putNull("effective_interval_ms");
      else o.put("effective_interval_ms", Math.max(1000, Collections.min(offers)));
      return o;
    }
    if (i.has("events")) {
      int clock = i.path("last_valid_inbound_ms").asInt();
      for (var e : i.path("events"))
        if (e.path("event").asText().equals("valid_inbound_frame")) clock = e.path("at_ms").asInt();
      return out().put("last_valid_inbound_ms", clock);
    }
    if (i.has("queued_probe_count")) {
      boolean due =
          i.path("evaluate_at_ms").asInt() - i.path("last_application_send_ms").asInt()
              >= i.path("effective_interval_ms").asInt();
      if (due && i.path("queued_probe_count").asInt() == 0)
        return with(out(), "enqueue", out().put("frame", "0x07").put("payload_length", 0))
            .put("queued_probe_count", 1);
      return out().put("queued_probe_count", i.path("queued_probe_count").asInt());
    }
    if (i.has("active_streams")) {
      if (i.path("evaluate_at_ms").asInt()
          >= i.path("last_valid_inbound_ms").asInt() + 3 * i.path("effective_interval_ms").asInt())
        return out()
            .put("state", "closing")
            .put("error", "NCP-KEEPALIVE-TIMEOUT")
            .put("error_count", 1)
            .put("cancelled_streams", i.path("active_streams").asInt())
            .put("close_by_ms", i.path("evaluate_at_ms").asInt() + 500)
            .put("allow_later_application_frames", false);
      return out().put("state", "open");
    }
    if (i.has("payload_length"))
      return i.path("payload_length").asInt() == 0
          ? out()
              .put("accepted", true)
              .put("last_valid_inbound_ms", i.path("received_at_ms").asInt())
          : out()
              .put("accepted", false)
              .put("error", "NCP-FRAME-PAYLOAD-TOO-LARGE")
              .put("last_valid_inbound_ms", i.path("last_valid_inbound_ms").asInt());
    if (i.has("early_data"))
      return i.path("carrier").asText().equals("quic") && !i.path("handshake_confirmed").asBoolean()
          ? out()
              .put("accepted", false)
              .put("error", "NCP-EARLY-DATA-REJECTED")
              .put("retry_after_confirmation", true)
          : out().put("accepted", true);
    if (i.has("bound_nid")) {
      if (!i.path("handshake_confirmed").asBoolean()
          || !i.path("bound_nid").asText().equals(i.path("migrated_nid").asText()))
        return out()
            .put("migration_allowed", false)
            .put("session_preserved", false)
            .put("error", "NCP-NID-MISMATCH");
      boolean send =
          i.path("carrier_credit_bytes").asInt() > 0 && i.path("ncp_window_cgn").asInt() > 0;
      var o =
          out()
              .put("migration_allowed", true)
              .put("session_preserved", true)
              .put("send_allowed", send);
      if (!send)
        o.put(
            "reason",
            i.path("ncp_window_cgn").asInt() <= 0
                ? "ncp_window_exhausted"
                : "carrier_credit_exhausted");
      return o;
    }
    throw new IllegalArgumentException("unknown NCP input");
  }

  private record Normalized(ObjectNode value, ArrayNode diagnostics) {}

  private static Normalized sla(JsonNode s, String prefix) {
    var o = out();
    var d = arr();
    if (s.has("p95_latency_ms")) {
      long n = s.path("p95_latency_ms").asLong();
      if (s.path("p95_latency_ms").isIntegralNumber() && n > 0 && n <= 0xffffffffL)
        o.put("p95_latency_ms", n);
      else d.add(prefix + "p95_latency_ms");
    }
    if (s.has("availability")) {
      double n = s.path("availability").asDouble();
      if (n > 0 && n <= 1) o.put("availability", s.path("availability").asText());
      else d.add(prefix + "availability");
    }
    if (s.has("sla_tier")) {
      String t = s.path("sla_tier").asText();
      Map<String, Integer> ranks = Map.of("basic", 0, "standard", 1, "premium", 2);
      if (ranks.containsKey(t)) o.put("sla_tier", t).put("sla_tier_rank", ranks.get(t));
      else o.put("sla_tier_raw", t).putNull("sla_tier_rank");
    }
    return new Normalized(o, d);
  }

  private static ObjectNode nwpMetadata(ObjectNode i) {
    if (i.has("stability")) {
      if (i.path("stability").isNull())
        return with(out().put("normalized", "stable"), "diagnostics", arr());
      String v = i.path("stability").asText();
      return Set.of("experimental", "stable", "deprecated").contains(v)
          ? out().put("raw", v).put("normalized", v).put("rank_as_stable", v.equals("stable"))
          : out().put("raw", v).put("normalized", "experimental").put("rank_as_stable", false);
    }
    if (i.has("sla")) {
      var n = sla(i.path("sla"), "");
      return with(
          with(out().put("manifest_valid", true), "normalized_sla", n.value()),
          "diagnostics",
          n.diagnostics());
    }
    if (i.has("billing")) {
      var b = i.path("billing");
      String p = b.path("metering_profile").asText();
      if (!Set.of("free", "metered").contains(p)) p = "metered";
      var n = out().put("metering_profile", p);
      var d = arr();
      if (p.equals("free")) {
        for (String k : List.of("billing_unit", "price_hint", "currency")) if (b.has(k)) d.add(k);
      } else {
        String unit = b.path("billing_unit").asText();
        if (!unit.isEmpty()) n.put("billing_unit", unit);
        else d.add("billing_unit");
        if (b.has("price_hint")) {
          String price = b.path("price_hint").asText();
          if (PRICE.matcher(price).matches()) {
            if (b.has("currency") && !b.path("currency").asText().equals(price.substring(0, 3)))
              d.add("currency");
            else {
              n.put("price_hint", price);
              if (b.has("currency")) n.put("currency", b.path("currency").asText());
            }
          } else d.add("price_hint");
        }
      }
      return with(with(out(), "normalized_billing", n), "diagnostics", d);
    }
    if (i.has("top_level")) {
      var b = sla(i.path("top_level").path("sla"), "");
      var a = sla(i.path("action").path("sla"), "action.sla.");
      a.value().fields().forEachRemaining(e -> b.value().set(e.getKey(), e.getValue()));
      return with(with(out(), "effective_sla", b.value()), "diagnostics", a.diagnostics());
    }
    throw new IllegalArgumentException("unknown metadata input");
  }

  private static ObjectNode nwpSubscription(ObjectNode i) {
    if (i.has("policy")) {
      var p = i.path("policy");
      var r = i.path("request");
      int def = p.path("default_lease_seconds").asInt(),
          max = p.path("max_lease_seconds").asInt(),
          renew = p.path("renew_before_seconds").asInt();
      if (def <= 0 || max <= 0 || def > max || renew >= max)
        return out()
            .put("accepted", false)
            .put("error", "NWP-SUBSCRIBE-LEASE-INVALID")
            .put("state_mutated", false);
      int lease = r.has("lease_seconds") ? r.path("lease_seconds").asInt() : def;
      if (lease <= 0)
        return out()
            .put("accepted", false)
            .put("error", "NWP-SUBSCRIBE-LEASE-INVALID")
            .put("state_mutated", false);
      lease = Math.min(lease, max);
      var o =
          out()
              .put("lease_seconds", lease)
              .put("expires_at", format(instant(i, "accepted_at").plusSeconds(lease)));
      if (!r.has("lease_seconds")) o.put("status", "open");
      return o;
    }
    if (i.has("owner_nid"))
      return i.path("owner_nid").asText().equals(i.path("caller_nid").asText())
          ? out().put("accepted", true)
          : out()
              .put("accepted", false)
              .put("error", "NWP-AUTH-NID-SCOPE-VIOLATION")
              .put("state_disclosed", false);
    if (i.has("prior_seq"))
      return out()
          .put(
              "expires_at",
              format(instant(i, "accepted_at").plusSeconds(i.path("lease_seconds").asLong())))
          .put("seq", i.path("prior_seq").asInt())
          .put("cursor", i.path("prior_cursor").asText());
    if (i.has("expires_at"))
      return !instant(i, "now").isBefore(instant(i, "expires_at"))
          ? out()
              .put("accepted", false)
              .put("status", "closed")
              .put("error", "NWP-SUBSCRIBE-LEASE-EXPIRED")
              .put("terminal_event_count", 1)
          : out().put("accepted", true);
    if (Set.of("renew", "close").contains(i.path("operation").asText()))
      for (String k : List.of("anchor_ref", "filter", "type"))
        if (i.has(k))
          return out()
              .put("accepted", false)
              .put("error", "NWP-SUBSCRIBE-LEASE-INVALID")
              .put("state_mutated", false);
    throw new IllegalArgumentException("unknown subscription input");
  }

  private static ObjectNode nipRenewal(ObjectNode i) {
    if (i.path("profile").asText().equals("standard")) {
      boolean open = !instant(i, "not_after").isAfter(instant(i, "now").plusSeconds(7 * 86400));
      var o = out().put("renewal_open", open);
      if (open) o.putNull("error");
      else o.put("error", "NIP-CA-RENEWAL-TOO-EARLY");
      return o;
    }
    if (i.path("profile").asText().equals("short-lived-edge")) {
      int w = i.path("original_validity_seconds").asInt() / 4;
      return out()
          .put("renewal_open", i.path("remaining_seconds").asInt() <= w)
          .put("window_seconds", w);
    }
    if (i.has("current")) {
      boolean allowed =
          subset(i.path("requested").path("capabilities"), i.path("current").path("capabilities"))
              && subset(i.path("requested").path("scope"), i.path("current").path("scope"));
      return allowed
          ? out().put("issued", true)
          : out().put("issued", false).put("error", "NIP-CA-SCOPE-EXPANSION-DENIED");
    }
    if (i.has("recorded")) {
      var r = i.path("recorded");
      return r.path("committed").asBoolean()
              && r.path("canonical_digest").asText().equals(i.path("canonical_digest").asText())
          ? out().put("serial", r.path("serial").asText()).put("new_issue_count", 0)
          : out().put("error", "NIP-CA-SERIAL-DUPLICATE").put("new_issue_count", 0);
    }
    if (i.has("old_ticket_not_after"))
      return out().put("old_ticket_not_after", i.path("old_ticket_not_after").asText());
    throw new IllegalArgumentException("unknown renewal input");
  }

  private static ObjectNode nipRevocation(ObjectNode i) {
    if (i.has("cached")) {
      var c = i.path("cached");
      var n = i.path("incoming");
      boolean replace =
          n.path("signature_valid").asBoolean()
              && instant(n, "this_update").isAfter(instant(c, "this_update"));
      return out()
          .put("cache_replaced", replace)
          .put("effective_outcome", (replace ? n : c).path("outcome").asText());
    }
    var consulted = arr();
    var diagnostics = arr();
    Instant now = i.has("now") ? instant(i, "now") : null;
    for (var s : i.path("sources")) {
      String name = s.path("source").asText(), outcome = s.path("outcome").asText();
      consulted.add(name);
      if (outcome.equals("unknown"))
        return out().put("valid", false).put("error", "NIP-OCSP-UNKNOWN");
      if (now != null && s.has("next_update") && !now.isBefore(instant(s, "next_update"))) {
        diagnostics.add(name + "_stale");
        continue;
      }
      if (outcome.equals("revoked"))
        return out().put("valid", false).put("error", "NIP-CERT-REVOKED");
      if (outcome.equals("good")) {
        var o = with(out().put("valid", true), "consulted_sources", consulted);
        if (!diagnostics.isEmpty()) with(o, "diagnostics", diagnostics);
        return o;
      }
    }
    return i.path("revocation_mode").asText().equals("required")
        ? out().put("valid", false).put("error", "NIP-REVOCATION-STATE-STALE")
        : with(out().put("valid", true), "consulted_sources", consulted);
  }

  private static ObjectNode nipAdvisory(ObjectNode i) {
    var ident = i.path("ident");
    var ext = i.path("certificate_extensions");
    var f = arr();
    if (!ident.path("assurance_level").asText().equals(ext.path("assurance_level").asText()))
      f.add(out().put("field", "assurance_level").put("error", "NIP-ASSURANCE-MISMATCH"));
    if (!subset(ident.path("capabilities"), ext.path("capabilities")))
      f.add(out().put("field", "capabilities").put("error", "NIP-CERT-CAPABILITIES-EXCEEDED"));
    if (!subset(ident.path("node_roles"), ext.path("node_roles")))
      f.add(out().put("field", "node_roles").put("error", "NIP-CERT-NODE-ROLES-MISMATCH"));
    if (ident.path("ocsp_staple").isNull())
      f.add(out().put("field", "ocsp_staple").put("error", "NIP-OCSP-STAPLE-EXPIRED"));
    var list = new ArrayList<JsonNode>();
    f.forEach(list::add);
    list.sort(Comparator.comparing(x -> x.path("field").asText()));
    f.removeAll();
    list.forEach(f::add);
    return with(
            out().put("accepted_current_request", !i.path("phase3_enforcement").asBoolean()),
            "findings",
            f)
        .put("state_mutated", false);
  }

  private static ObjectNode ndp(ObjectNode i) {
    if (i.has("commit"))
      return i.path("commit").asText().equals("success")
          ? out()
              .put("acknowledged", true)
              .put("served_seq", i.path("incoming_seq").asInt())
              .put("persisted_seq", i.path("incoming_seq").asInt())
          : out()
              .put("acknowledged", false)
              .put("served_seq", i.path("persisted_seq").asInt())
              .put("persisted_seq", i.path("persisted_seq").asInt())
              .put("error", "NDP-STATE-UNAVAILABLE");
    if (i.has("now")) {
      var r = i.path("record");
      return out()
          .put("live_entry", instant(r, "fresh_until").isAfter(instant(i, "now")))
          .put("highest_seq", r.path("highest_seq").asInt())
          .put("ready", true);
    }
    if (i.has("restored_highest_seq"))
      return i.path("incoming_seq").asInt() < i.path("restored_highest_seq").asInt()
          ? out()
              .put("accepted", false)
              .put("highest_seq", i.path("restored_highest_seq").asInt())
              .put("error", "NDP-GRAPH-SEQ-ROLLBACK")
          : out().put("accepted", true).put("highest_seq", i.path("incoming_seq").asInt());
    if (i.has("owners")) {
      int top = Integer.MIN_VALUE;
      for (var x : i.path("owners"))
        if (x.path("live").asBoolean()) top = Math.max(top, x.path("epoch").asInt());
      var leaders = new ArrayList<String>();
      for (var x : i.path("owners"))
        if (x.path("live").asBoolean() && x.path("epoch").asInt() == top)
          leaders.add(x.path("nid").asText());
      Collections.sort(leaders);
      return leaders.size() == 1
          ? out().put("resolved_nid", leaders.getFirst())
          : out().putNull("resolved_nid").put("error", "NDP-CLUSTER-SPLIT");
    }
    if (i.has("snapshot_validation"))
      return i.path("snapshot_validation").asText().equals("valid")
          ? out().put("ready", true).put("started_empty", false)
          : out().put("ready", false).put("started_empty", false).put("error", "NDP-STATE-CORRUPT");
    if (i.has("profiles")) {
      var a = arr();
      for (var x : i.path("profiles"))
        a.add(x.asText().equals("local-dev") ? "volatile" : "durable");
      return out().set("recovery", a);
    }
    if (i.has("revoked_origin")) {
      var r = i.path("record");
      return out()
          .put(
              "live",
              r.path("live").asBoolean()
                  && !r.path("origin").asText().equals(i.path("revoked_origin").asText()))
          .put("highest_seq", r.path("highest_seq").asInt());
    }
    throw new IllegalArgumentException("unknown NDP input");
  }

  private static ObjectNode nop(ObjectNode i) {
    if (i.has("recorded")
        && i.path("recorded").path("digest").asText().equals(i.path("digest").asText())) {
      var r = i.path("recorded");
      return out()
          .put("state", r.path("state").asText())
          .put("dispatch_count", r.path("dispatch_count").asInt())
          .put("replayed", true);
    }
    if (i.has("recorded_digest"))
      return !i.path("digest").asText().equals(i.path("recorded_digest").asText())
          ? out()
              .put("accepted", false)
              .put("error", "NOP-REPLAY-CONFLICT")
              .put("record_mutated", false)
          : out().put("accepted", true);
    if (i.has("incoming")) {
      var n = i.path("incoming");
      boolean found = false;
      for (var r : i.path("records"))
        found |=
            r.path("caller_nid").asText().equals(n.path("caller_nid").asText())
                && r.path("task_id").asText().equals(n.path("task_id").asText());
      return out().put("new_key", !found).put("accepted", !found);
    }
    if (i.has("terminal_commit_ms"))
      return i.path("query_at_ms").asLong()
              >= i.path("terminal_commit_ms").asLong()
                  + 1000L * i.path("result_ttl_seconds").asLong()
          ? out().putNull("result").put("error", "NOP-TASK-RESULT-EXPIRED")
          : out().put("result", "retained");
    if (i.has("result_expired_at_ms")) {
      boolean retained =
          i.path("duplicate_at_ms").asLong()
              < i.path("result_expired_at_ms").asLong()
                  + 1000L * i.path("replay_tombstone_seconds").asLong();
      return retained
          ? out()
              .put("dispatch", false)
              .put("error", "NOP-TASK-RESULT-EXPIRED")
              .put("tombstone_retained", true)
          : out().put("dispatch", true).put("tombstone_retained", false);
    }
    if (i.has("capacity")) {
      var safe = new ArrayList<JsonNode>();
      for (var r : i.path("records")) if (!r.path("state").asText().equals("running")) safe.add(r);
      if (i.path("records").size() >= i.path("capacity").asInt() && safe.isEmpty())
        return with(out().put("accepted", false), "evicted", arr())
            .put("error", "NOP-REPLAY-LIMIT");
      var e = arr();
      if (!safe.isEmpty()) e.add(safe.getFirst().path("key"));
      return with(out().put("accepted", true), "evicted", e);
    }
    if (i.has("committed"))
      return out()
          .put("state", i.path("committed").path("state").asText())
          .put("late_event", "audit_only")
          .put("ttl_extended", false);
    if (i.has("min_required")) {
      var results = new ArrayList<JsonNode>();
      for (var r : i.path("results")) {
        if (!r.has("score") || !Double.isFinite(r.path("score").asDouble()))
          return out().put("error", "NOP-AGGREGATION-INVALID");
        results.add(r);
      }
      results.sort(
          Comparator.<JsonNode>comparingDouble(x -> x.path("score").asDouble())
              .reversed()
              .thenComparing(x -> x.path("node_id").asText()));
      var ids = arr();
      for (int x = 0; x < i.path("min_required").asInt(); x++)
        ids.add(results.get(x).path("node_id").asText());
      return with(out(), "selected_node_ids", ids);
    }
    if (i.has("topology_order")) {
      var by = new HashMap<String, JsonNode>();
      i.path("results").forEach(x -> by.put(x.path("node_id").asText(), x));
      var agg = out();
      for (var id : i.path("topology_order")) {
        var r = by.get(id.asText());
        if (r == null || !r.path("state").asText().equals("completed")) continue;
        r.path("value")
            .fields()
            .forEachRemaining(
                e -> {
                  if (agg.path(e.getKey()).isArray() && e.getValue().isArray())
                    ((ArrayNode) agg.path(e.getKey())).addAll((ArrayNode) e.getValue().deepCopy());
                  else agg.set(e.getKey(), e.getValue().deepCopy());
                });
      }
      return with(out(), "aggregated", agg).put("inputs_mutated", false);
    }
    throw new IllegalArgumentException("unknown NOP input");
  }

  private static ObjectNode out() {
    return F.objectNode();
  }

  private static ArrayNode arr() {
    return F.arrayNode();
  }

  private static ObjectNode with(ObjectNode o, String key, JsonNode value) {
    o.set(key, value);
    return o;
  }

  private static Instant instant(JsonNode n, String field) {
    return Instant.parse(n.path(field).asText());
  }

  private static String format(Instant i) {
    return DateTimeFormatter.ISO_INSTANT.format(i);
  }

  private static boolean subset(JsonNode want, JsonNode have) {
    var set = new HashSet<String>();
    have.forEach(x -> set.add(x.asText()));
    for (var x : want) if (!set.contains(x.asText())) return false;
    return true;
  }
}
