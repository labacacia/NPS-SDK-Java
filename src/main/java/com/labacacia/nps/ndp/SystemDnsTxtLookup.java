// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.ndp;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * {@link DnsTxtLookup} implementation that queries real DNS via JNDI.
 * Uses the Sun DNS context factory ({@code com.sun.jndi.dns.DnsContextFactory}).
 * Returns an empty list on any lookup failure instead of propagating exceptions.
 */
public final class SystemDnsTxtLookup implements DnsTxtLookup {

    @Override
    public List<String> lookup(String hostname) throws Exception {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", "dns:");

        List<String> result = new ArrayList<>();
        try {
            InitialDirContext ctx = new InitialDirContext(env);
            try {
                Attributes attrs = ctx.getAttributes(hostname, new String[]{"TXT"});
                Attribute txt = attrs.get("TXT");
                if (txt != null) {
                    for (int i = 0; i < txt.size(); i++) {
                        Object val = txt.get(i);
                        if (val != null) {
                            result.add(val.toString());
                        }
                    }
                }
            } finally {
                ctx.close();
            }
        } catch (NamingException e) {
            // DNS resolution failure — return empty list gracefully
        }
        return result;
    }
}
