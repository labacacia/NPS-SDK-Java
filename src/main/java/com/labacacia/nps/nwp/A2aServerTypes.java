// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nwp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** A2A protocol types implemented by the Bridge server adapter (A2A v0.2). */
public final class A2aServerTypes {

    private A2aServerTypes() {}

    /** A2A protocol version implemented by the Bridge server adapter. */
    public static final String PROTOCOL_VERSION = "0.2";

    public static final class TaskState {
        private TaskState() {}
        public static final String COMPLETED = "completed";
        public static final String FAILED = "failed";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class AgentCard {
        public String name;
        public String description;
        public String url;
        public AgentProvider provider;
        public String version;
        public AgentCapabilities capabilities;
        public AgentAuthentication authentication;
        public List<String> defaultInputModes = List.of("text", "data");
        public List<String> defaultOutputModes = List.of("text", "data");
        public List<AgentSkill> skills;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class AgentProvider {
        public String organization;
        public String url;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class AgentCapabilities {
        public boolean streaming;
        public boolean pushNotifications;
        public boolean stateTransitionHistory;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class AgentAuthentication {
        public List<String> schemes;
        public String credentials;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class AgentSkill {
        public String id;
        public String name;
        public String description;
        public List<String> tags;
        public List<String> inputModes;
        public List<String> outputModes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Task {
        public String id;
        public String sessionId;
        public TaskStatus status;
        public List<Artifact> artifacts;
        public List<Message> history;
        public JsonNode metadata;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class TaskStatus {
        public String state;
        public Message message;
        public String timestamp;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Message {
        public String role;
        public List<Part> parts;
        public JsonNode metadata;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Part {
        public String type;
        public String text;
        public JsonNode data;
        public JsonNode metadata;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Artifact {
        public String name;
        public String description;
        public List<Part> parts;
        public int index;
        public JsonNode metadata;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class SendTaskParams {
        public String id;
        public String sessionId;
        public Message message;
        public JsonNode metadata;
    }
}
