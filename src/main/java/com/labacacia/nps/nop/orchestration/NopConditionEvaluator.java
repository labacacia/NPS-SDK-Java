// Copyright 2026 INNO LOTUS PTY LTD
// SPDX-License-Identifier: Apache-2.0
package com.labacacia.nps.nop.orchestration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates CEL-subset condition expressions used in DAG node {@code condition}
 * fields (NPS-5 §3.1.5).
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>Comparison: {@code $.node.field > 0.7}, {@code $.node.status == "ok"}, {@code $.n.x != null}</li>
 *   <li>Boolean logic: {@code &&}, {@code ||}, {@code !}</li>
 *   <li>Grouping: {@code ( expr )}</li>
 *   <li>Literals: numbers, quoted strings, {@code true}, {@code false}, {@code null}</li>
 *   <li>JSONPath access: {@code $.node_id.field.sub} (via {@link NopInputMapper#resolve})</li>
 * </ul>
 */
public final class NopConditionEvaluator {
    private NopConditionEvaluator() {}

    /**
     * Evaluates {@code condition} in the context of completed node results.
     * Returns {@code true} if the node should execute; {@code false} if it should be skipped.
     *
     * @throws NopConditionException for syntax errors or unresolvable paths.
     */
    public static boolean evaluate(String condition, Map<String, JsonNode> context) {
        if (condition == null || condition.isBlank()) return true;

        try {
            List<Token> tokens = tokenize(condition.trim());
            return new Parser(tokens, context).parseOrExpr();
        } catch (NopConditionException e) {
            throw e;
        } catch (Exception e) {
            throw new NopConditionException("Condition evaluation error: " + e.getMessage(), condition);
        }
    }

    // ── Tokenizer ─────────────────────────────────────────────────────────────

    private enum Kind {
        DOLLAR_PATH, NUMBER, STRING,
        TRUE, FALSE, NULL,
        GT, GTE, LT, LTE, EQ, NEQ,
        AND, OR, NOT,
        LPAREN, RPAREN,
        EOF
    }

    private record Token(Kind kind, String raw) {}

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = input.length();

        while (i < n) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }

            // Dollar path
            if (c == '$' && i + 1 < n && input.charAt(i + 1) == '.') {
                int start = i;
                while (i < n) {
                    char d = input.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '.' || d == '$') i++;
                    else break;
                }
                tokens.add(new Token(Kind.DOLLAR_PATH, input.substring(start, i)));
                continue;
            }

            // String literal
            if (c == '"') {
                int start = i++;
                while (i < n && input.charAt(i) != '"') i++;
                i++; // closing quote
                tokens.add(new Token(Kind.STRING, input.substring(start + 1, i - 1)));
                continue;
            }

            // Number
            if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(input.charAt(i + 1)))) {
                int start = i;
                if (c == '-') i++;
                while (i < n && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) i++;
                tokens.add(new Token(Kind.NUMBER, input.substring(start, i)));
                continue;
            }

            // Operators
            if (c == '>' && i + 1 < n && input.charAt(i + 1) == '=') { tokens.add(new Token(Kind.GTE, ">=")); i += 2; continue; }
            if (c == '<' && i + 1 < n && input.charAt(i + 1) == '=') { tokens.add(new Token(Kind.LTE, "<=")); i += 2; continue; }
            if (c == '=' && i + 1 < n && input.charAt(i + 1) == '=') { tokens.add(new Token(Kind.EQ,  "==")); i += 2; continue; }
            if (c == '!' && i + 1 < n && input.charAt(i + 1) == '=') { tokens.add(new Token(Kind.NEQ, "!=")); i += 2; continue; }
            if (c == '&' && i + 1 < n && input.charAt(i + 1) == '&') { tokens.add(new Token(Kind.AND, "&&")); i += 2; continue; }
            if (c == '|' && i + 1 < n && input.charAt(i + 1) == '|') { tokens.add(new Token(Kind.OR,  "||")); i += 2; continue; }
            if (c == '>') { tokens.add(new Token(Kind.GT,  ">")); i++; continue; }
            if (c == '<') { tokens.add(new Token(Kind.LT,  "<")); i++; continue; }
            if (c == '!') { tokens.add(new Token(Kind.NOT, "!")); i++; continue; }
            if (c == '(') { tokens.add(new Token(Kind.LPAREN, "(")); i++; continue; }
            if (c == ')') { tokens.add(new Token(Kind.RPAREN, ")")); i++; continue; }

            // Keywords: true, false, null
            if (Character.isLetter(c)) {
                int start = i;
                while (i < n && Character.isLetterOrDigit(input.charAt(i))) i++;
                String kw = input.substring(start, i);
                switch (kw) {
                    case "true"  -> tokens.add(new Token(Kind.TRUE,  "true"));
                    case "false" -> tokens.add(new Token(Kind.FALSE, "false"));
                    case "null"  -> tokens.add(new Token(Kind.NULL,  "null"));
                    default -> throw new NopConditionException("Unknown token '" + kw + "'.", input);
                }
                continue;
            }

            throw new NopConditionException("Unexpected character '" + c + "' at position " + i + ".", input);
        }

        tokens.add(new Token(Kind.EOF, ""));
        return tokens;
    }

    // ── Recursive-descent parser ──────────────────────────────────────────────

    /** A resolved value tagged with its originating token kind. */
    private record Value(Kind kind, Object value) {}

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, JsonNode> context;
        private int pos;

        Parser(List<Token> tokens, Map<String, JsonNode> context) {
            this.tokens = tokens;
            this.context = context;
        }

        private Token current() { return tokens.get(pos); }
        private Token consume()  { return tokens.get(pos++); }

        // or_expr := and_expr ('||' and_expr)*
        boolean parseOrExpr() {
            boolean left = parseAndExpr();
            // Right operand is always parsed (tokens consumed) so the grammar stays in sync.
            while (current().kind() == Kind.OR) { consume(); boolean right = parseAndExpr(); left = left || right; }
            return left;
        }

        // and_expr := not_expr ('&&' not_expr)*
        private boolean parseAndExpr() {
            boolean left = parseNotExpr();
            while (current().kind() == Kind.AND) { consume(); boolean right = parseNotExpr(); left = left && right; }
            return left;
        }

        // not_expr := '!' not_expr | comparison
        private boolean parseNotExpr() {
            if (current().kind() == Kind.NOT) { consume(); return !parseNotExpr(); }
            return parseComparison();
        }

        // comparison := '(' or_expr ')' | true | false | value (op value)?
        private boolean parseComparison() {
            if (current().kind() == Kind.LPAREN) {
                consume(); // '('
                boolean inner = parseOrExpr();
                if (current().kind() != Kind.RPAREN) {
                    throw new NopConditionException("Expected ')'.", "");
                }
                consume();
                return inner;
            }

            if (current().kind() == Kind.TRUE)  { consume(); return true;  }
            if (current().kind() == Kind.FALSE) { consume(); return false; }

            Value lhs = parseValue();

            Kind opKind = current().kind();
            if (!isComparisonOp(opKind)) return asTruthy(lhs);

            consume(); // operator
            Value rhs = parseValue();
            return compare(lhs, opKind, rhs);
        }

        private static boolean isComparisonOp(Kind k) {
            return k == Kind.GT || k == Kind.GTE || k == Kind.LT
                || k == Kind.LTE || k == Kind.EQ || k == Kind.NEQ;
        }

        // value := dollar_path | number | string | null | true | false
        private Value parseValue() {
            Token tok = current();
            consume();
            return switch (tok.kind()) {
                case DOLLAR_PATH -> new Value(Kind.DOLLAR_PATH, resolvePath(tok.raw()));
                case NUMBER      -> new Value(Kind.NUMBER, Double.parseDouble(tok.raw()));
                case STRING      -> new Value(Kind.STRING, tok.raw());
                case TRUE        -> new Value(Kind.TRUE,  Boolean.TRUE);
                case FALSE       -> new Value(Kind.FALSE, Boolean.FALSE);
                case NULL        -> new Value(Kind.NULL,  null);
                default -> throw new NopConditionException("Expected a value, got '" + tok.raw() + "'.", "");
            };
        }

        private Object resolvePath(String path) {
            JsonNode node = NopInputMapper.resolve(path, context);
            if (node == null || node.isNull()) return null;
            if (node.isNumber())  return node.asDouble();
            if (node.isTextual()) return node.asText();
            if (node.isBoolean()) return node.asBoolean();
            return node.toString(); // object/array as string
        }

        private static boolean asTruthy(Value v) {
            Object o = v.value();
            if (o instanceof Boolean b) return b;
            if (o instanceof Double d)  return d != 0;
            if (o instanceof String s)  return !s.isEmpty();
            if (o == null)              return false;
            return true;
        }

        private static boolean compare(Value lhs, Kind op, Value rhs) {
            Object l = lhs.value();
            Object r = rhs.value();

            // Null / equality comparisons
            if (op == Kind.EQ)  return Objects.equals(l, r);
            if (op == Kind.NEQ) return !Objects.equals(l, r);
            if (l == null || r == null) return false;

            // Numeric comparisons
            if (l instanceof Double ld && r instanceof Double rd) {
                return switch (op) {
                    case GT  -> ld >  rd;
                    case GTE -> ld >= rd;
                    case LT  -> ld <  rd;
                    case LTE -> ld <= rd;
                    default  -> false;
                };
            }

            // String comparisons (ordinal)
            if (l instanceof String ls && r instanceof String rs) {
                int cmp = ls.compareTo(rs);
                return switch (op) {
                    case GT  -> cmp > 0;
                    case GTE -> cmp >= 0;
                    case LT  -> cmp < 0;
                    case LTE -> cmp <= 0;
                    default  -> false;
                };
            }

            return false;
        }
    }
}
