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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tier-3: BinaryVector v1 codec. */
public final class Tier3BinaryVectorCodec {

    private static final byte[] MAGIC = new byte[] { 'N', 'P', 'B', 'V' };
    private static final byte VERSION = 1;
    private static final int PREFIX_SIZE = 16;
    private static final String MARKER_KEY = "$nps_binary_vector";

    public byte[] encode(NpsFrame frame) {
        try {
            Map<String, Object> metadata = deepCopyMap(frame.toDict());
            List<float[]> vectors = new ArrayList<>();
            extractVectorSearchVector(metadata, vectors);

            if (vectors.size() > 0xFFFF) {
                throw new NpsCodecError("BinaryVector supports at most 65535 vectors per frame");
            }

            byte[] metadataBytes = packToMsgPack(metadata);
            int segmentBytes = 0;
            for (float[] vector : vectors) segmentBytes += 4 + vector.length * 4;

            ByteArrayOutputStream out = new ByteArrayOutputStream(PREFIX_SIZE + metadataBytes.length + segmentBytes);
            ByteBuffer prefix = ByteBuffer.allocate(PREFIX_SIZE).order(ByteOrder.BIG_ENDIAN);
            prefix.put(MAGIC);
            prefix.put(VERSION);
            prefix.put((byte) 0);
            prefix.putShort((short) vectors.size());
            prefix.putInt(metadataBytes.length);
            prefix.putInt(0);
            out.write(prefix.array());
            out.write(metadataBytes);

            for (float[] vector : vectors) {
                out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(vector.length).array());
                for (float value : vector) {
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array());
                }
            }

            return out.toByteArray();
        } catch (NpsCodecError e) {
            throw e;
        } catch (Exception e) {
            throw new NpsCodecError("BinaryVector encode failed: " + e.getMessage(), e);
        }
    }

    public NpsFrame decode(FrameType frameType, byte[] payload, FrameRegistry registry) {
        try {
            Map<String, Object> dict = decodePayload(payload);
            return registry.resolve(frameType).decode(dict);
        } catch (NpsCodecError e) {
            throw e;
        } catch (Exception e) {
            throw new NpsCodecError("BinaryVector decode failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodePayload(byte[] payload) throws Exception {
        if (payload.length < PREFIX_SIZE) {
            throw new NpsCodecError("BinaryVector payload too short: " + payload.length + " bytes");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (payload[i] != MAGIC[i]) throw new NpsCodecError("BinaryVector payload magic mismatch");
        }
        if (payload[4] != VERSION) {
            throw new NpsCodecError("Unsupported BinaryVector version: " + payload[4]);
        }

        ByteBuffer prefix = ByteBuffer.wrap(payload, 0, PREFIX_SIZE).order(ByteOrder.BIG_ENDIAN);
        int flags = payload[5] & 0xFF;
        int vectorCount = Short.toUnsignedInt(prefix.getShort(6));
        int metadataLength = prefix.getInt(8);
        int reserved = prefix.getInt(12);
        if (flags != 0 || reserved != 0) {
            throw new NpsCodecError("BinaryVector reserved fields must be zero");
        }
        if (metadataLength < 0 || metadataLength > payload.length - PREFIX_SIZE) {
            throw new NpsCodecError("BinaryVector metadata length exceeds payload length");
        }

        int offset = PREFIX_SIZE;
        Map<String, Object> metadata;
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(payload, offset, metadataLength)) {
            metadata = (Map<String, Object>) fromValue(unpacker.unpackValue());
        }
        offset += metadataLength;

        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < vectorCount; i++) {
            if (payload.length - offset < 4) {
                throw new NpsCodecError("BinaryVector segment missing dimension");
            }
            long dim = Integer.toUnsignedLong(ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt());
            offset += 4;
            long byteLength = dim * 4L;
            if (dim > Integer.MAX_VALUE || payload.length - offset < byteLength) {
                throw new NpsCodecError("BinaryVector segment is truncated");
            }

            float[] vector = new float[(int) dim];
            for (int j = 0; j < vector.length; j++) {
                vector[j] = ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                offset += 4;
            }
            vectors.add(vector);
        }

        if (offset != payload.length) {
            throw new NpsCodecError("BinaryVector payload has trailing bytes");
        }

        restoreVectorSearchVector(metadata, vectors);
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private void extractVectorSearchVector(Map<String, Object> metadata, List<float[]> vectors) {
        Object vectorSearchObj = metadata.get("vector_search");
        if (!(vectorSearchObj instanceof Map<?, ?> rawVectorSearch)) return;
        Map<String, Object> vectorSearch = (Map<String, Object>) rawVectorSearch;
        Object vectorObj = vectorSearch.get("vector");
        if (!(vectorObj instanceof List<?> list)) return;

        float[] vector = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Number n)) return;
            float value = n.floatValue();
            if (!Float.isFinite(value)) return;
            vector[i] = value;
        }

        int index = vectors.size();
        vectors.add(vector);
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put(MARKER_KEY, index);
        marker.put("dtype", "float32");
        marker.put("dim", vector.length);
        vectorSearch.put("vector", marker);
    }

    @SuppressWarnings("unchecked")
    private void restoreVectorSearchVector(Map<String, Object> metadata, List<float[]> vectors) {
        Object vectorSearchObj = metadata.get("vector_search");
        if (!(vectorSearchObj instanceof Map<?, ?> rawVectorSearch)) return;
        Map<String, Object> vectorSearch = (Map<String, Object>) rawVectorSearch;
        if (!vectorSearch.containsKey("vector")) return;
        Object markerObj = vectorSearch.get("vector");
        if (!(markerObj instanceof Map<?, ?> rawMarker)) {
            throw new NpsCodecError("BinaryVector marker must be an object");
        }
        Map<String, Object> marker = (Map<String, Object>) rawMarker;

        Object indexObj = marker.get(MARKER_KEY);
        if (!(indexObj instanceof Number indexNumber)) {
            throw new NpsCodecError("BinaryVector marker missing vector index");
        }
        int index = indexNumber.intValue();
        if (index < 0 || index >= vectors.size()) {
            throw new NpsCodecError(
                "BinaryVector marker references vector " + index + ", but only " + vectors.size() + " vectors are present");
        }
        if (!"float32".equals(marker.get("dtype"))) {
            throw new NpsCodecError("BinaryVector v1 only supports dtype=float32");
        }
        Object dimObj = marker.get("dim");
        if (!(dimObj instanceof Number dimNumber) || dimNumber.intValue() != vectors.get(index).length) {
            throw new NpsCodecError("BinaryVector marker dimension does not match vector segment");
        }

        List<Float> vector = new ArrayList<>();
        for (float value : vectors.get(index)) vector.add(value);
        vectorSearch.put("vector", vector);
    }

    private byte[] packToMsgPack(Map<String, Object> metadata) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             MessagePacker packer = MessagePack.newDefaultPacker(baos)) {
            packObject(packer, metadata);
            packer.flush();
            return baos.toByteArray();
        }
    }

    private void packObject(MessagePacker packer, Object obj) throws Exception {
        if (obj == null) {
            packer.packNil();
        } else if (obj instanceof Boolean b) {
            packer.packBoolean(b);
        } else if (obj instanceof Byte || obj instanceof Short || obj instanceof Integer || obj instanceof Long) {
            packer.packLong(((Number) obj).longValue());
        } else if (obj instanceof Number n) {
            packer.packDouble(n.doubleValue());
        } else if (obj instanceof String s) {
            packer.packString(s);
        } else if (obj instanceof List<?> list) {
            packer.packArrayHeader(list.size());
            for (Object item : list) packObject(packer, item);
        } else if (obj instanceof Map<?, ?> map) {
            packer.packMapHeader(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                packer.packString(String.valueOf(entry.getKey()));
                packObject(packer, entry.getValue());
            }
        } else {
            packer.packString(obj.toString());
        }
    }

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
                List<Object> list = new ArrayList<>();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopy(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) copy.add(deepCopy(item));
            return copy;
        }
        return value;
    }
}
