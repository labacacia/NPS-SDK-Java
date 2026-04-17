// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.core.codec;

import com.labacacia.nps.core.FrameType;
import com.labacacia.nps.core.NpsFrame;
import com.labacacia.nps.core.exception.NpsCodecError;
import com.labacacia.nps.core.registry.FrameRegistry;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tier-2: MsgPack codec using msgpack-core. */
public final class Tier2MsgPackCodec {

    public byte[] encode(NpsFrame frame) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             MessagePacker packer = MessagePack.newDefaultPacker(baos)) {
            packValue(packer, toValue(frame.toDict()));
            packer.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new NpsCodecError("MsgPack encode failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public NpsFrame decode(FrameType frameType, byte[] payload, FrameRegistry registry) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(payload)) {
            Value value = unpacker.unpackValue();
            Map<String, Object> dict = (Map<String, Object>) fromValue(value);
            return registry.resolve(frameType).decode(dict);
        } catch (NpsCodecError e) {
            throw e;
        } catch (Exception e) {
            throw new NpsCodecError("MsgPack decode failed: " + e.getMessage(), e);
        }
    }

    // ── Value conversion ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Value toValue(Object obj) {
        if (obj == null)                      return ValueFactory.newNil();
        if (obj instanceof Boolean b)         return ValueFactory.newBoolean(b);
        if (obj instanceof Integer i)         return ValueFactory.newInteger(i);
        if (obj instanceof Long l)            return ValueFactory.newInteger(l);
        if (obj instanceof Number n)          return ValueFactory.newFloat(n.doubleValue());
        if (obj instanceof String s)          return ValueFactory.newString(s);
        if (obj instanceof java.util.List<?> list) {
            Value[] vals = list.stream().map(this::toValue).toArray(Value[]::new);
            return ValueFactory.newArray(vals);
        }
        if (obj instanceof Map<?, ?> map) {
            Value[] kvs = new Value[map.size() * 2];
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                kvs[i++] = ValueFactory.newString(e.getKey().toString());
                kvs[i++] = toValue(e.getValue());
            }
            return ValueFactory.newMap(kvs);
        }
        return ValueFactory.newString(obj.toString());
    }

    private void packValue(MessagePacker p, Value v) throws Exception {
        p.packValue(v);
    }

    @SuppressWarnings("unchecked")
    private Object fromValue(Value v) {
        return switch (v.getValueType()) {
            case NIL     -> null;
            case BOOLEAN -> v.asBooleanValue().getBoolean();
            case INTEGER -> {
                long l = v.asIntegerValue().asLong();
                yield (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (int) l : l;
            }
            case FLOAT   -> v.asFloatValue().toDouble();
            case STRING  -> v.asStringValue().asString();
            case ARRAY   -> {
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (Value item : v.asArrayValue()) list.add(fromValue(item));
                yield list;
            }
            case MAP -> {
                Map<String, Object> map = new LinkedHashMap<>();
                v.asMapValue().entrySet().forEach(e ->
                    map.put(e.getKey().asStringValue().asString(), fromValue(e.getValue())));
                yield map;
            }
            default -> v.toString();
        };
    }
}
