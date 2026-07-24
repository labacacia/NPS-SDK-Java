// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parser and accessors for the {@code bridge_target} action parameter.
 *
 * <p>In the Java SDK {@code ActionFrame.params} and {@code BridgeTarget.extras}
 * are decoded JSON {@code Map<String,Object>} trees rather than raw JSON
 * elements; the accessors below operate on those decoded values.
 */
public final class BridgeTargetParser {

    private BridgeTargetParser() {}

    /** Parse {@code params.bridge_target} from an action frame. */
    public static BridgeTarget fromActionFrame(ActionFrame frame) {
        if (frame == null) {
            throw new NullPointerException("frame");
        }
        Map<String, Object> parameters = frame.params();
        if (parameters == null) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.TARGET_INVALID, "params.bridge_target is required.");
        }

        Object targetElement = parameters;
        Object nested = parameters.get("bridge_target");
        if (nested instanceof Map<?, ?>) {
            targetElement = nested;
        }

        return fromJson(targetElement);
    }

    /** Parse a bridge target JSON object. */
    @SuppressWarnings("unchecked")
    public static BridgeTarget fromJson(Object targetElement) {
        if (!(targetElement instanceof Map<?, ?> map)) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.TARGET_INVALID, "bridge_target must be an object.");
        }

        Map<String, Object> obj = (Map<String, Object>) map;
        String protocol = readRequiredString(obj, "protocol");
        String endpoint = readRequiredString(obj, "endpoint");
        Map<String, Object> extras = new LinkedHashMap<>();

        for (Map.Entry<String, Object> property : obj.entrySet()) {
            String name = property.getKey();
            if ("protocol".equals(name) || "endpoint".equals(name)) {
                continue;
            }

            if ("extras".equals(name) && property.getValue() instanceof Map<?, ?> extrasMap) {
                for (Map.Entry<?, ?> extra : extrasMap.entrySet()) {
                    extras.put(String.valueOf(extra.getKey()), extra.getValue());
                }
                continue;
            }

            extras.put(name, property.getValue());
        }

        return new BridgeTarget(protocol, endpoint, extras.isEmpty() ? null : extras);
    }

    /** Read a string extra from a target. */
    public static String getString(BridgeTarget target, String name) {
        return getString(target, name, null);
    }

    /** Read a string extra from a target, with a default. */
    public static String getString(BridgeTarget target, String name, String defaultValue) {
        Object value = extra(target, name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }
        return String.valueOf(value);
    }

    /**
     * Try to read a raw extra value from a target. Returns {@code null} when the
     * extra is absent. Values are decoded JSON trees (Map/List/String/Number/Boolean).
     */
    public static Object getJson(BridgeTarget target, String name) {
        return extra(target, name);
    }

    private static Object extra(BridgeTarget target, String name) {
        if (target == null || target.extras == null) {
            return null;
        }
        // extras keys are case-sensitive here (matches the raw wire object).
        if (target.extras.containsKey(name)) {
            return target.extras.get(name);
        }
        for (Map.Entry<String, Object> e : target.extras.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String readRequiredString(Map<String, Object> obj, String name) {
        Object value = obj.get(name);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new BridgeDispatchException(
                BridgeErrorCodes.TARGET_INVALID, "bridge_target." + name + " is required.");
        }
        return s;
    }
}
