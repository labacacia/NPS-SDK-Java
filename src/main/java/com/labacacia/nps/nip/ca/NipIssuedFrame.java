// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nip.ca;

import java.util.List;
import java.util.Map;

/**
 * An issued IdentFrame (0x20) as produced by {@link NipCaService}. The wire
 * form is exposed as an ordered {@link #toDict()} map whose keys match the .NET
 * reference exactly ({@code frame}, {@code nid}, {@code pub_key},
 * {@code capabilities}, {@code scope}, {@code issued_by}, {@code issued_at},
 * {@code expires_at}, {@code serial}, {@code signature}, and — when present —
 * {@code assurance_level}, {@code lineage}, {@code metadata}, {@code cert_format},
 * {@code cert_chain}).
 */
public final class NipIssuedFrame {

    private final Map<String, Object> dict;

    NipIssuedFrame(Map<String, Object> dict) { this.dict = dict; }

    /** The full wire dict (ordered, snake_case keys). */
    public Map<String, Object> toDict() { return dict; }

    public String nid()       { return (String) dict.get("nid"); }
    public String pubKey()    { return (String) dict.get("pub_key"); }
    public String serial()    { return (String) dict.get("serial"); }
    public String issuedBy()  { return (String) dict.get("issued_by"); }
    public String issuedAt()  { return (String) dict.get("issued_at"); }
    public String expiresAt() { return (String) dict.get("expires_at"); }
    public String signature() { return (String) dict.get("signature"); }

    @SuppressWarnings("unchecked")
    public List<String> capabilities() { return (List<String>) dict.get("capabilities"); }

    public Object scope()     { return dict.get("scope"); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> lineage() { return (Map<String, Object>) dict.get("lineage"); }

    public String certFormat() { return (String) dict.get("cert_format"); }

    @SuppressWarnings("unchecked")
    public List<String> certChain() { return (List<String>) dict.get("cert_chain"); }
}
