package dev.omatheusmesmo.vintelli.tui.icons;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the kind of the first top-level type of a Java source without parsing
 * it. Package and import statements, comments, string and character literals,
 * annotations with their arguments and the declaration modifiers are skipped;
 * the first declaration keyword decides. Anything else before a declaration,
 * such as a field or a method, means that the text is not a type declaration
 * and the result is empty.
 */
public final class JavaTypeScanner {

    private static final Set<String> MODIFIERS = Set.of(
            "public", "protected", "private", "static", "abstract", "final", "strictfp", "sealed");
    private static final String BYTE_ORDER_MARK = "﻿";
    private static final String TEXT_BLOCK = "\"\"\"";

    private JavaTypeScanner() {
    }

    public static Optional<JavaTypeKind> scan(String source) {
        return declaration(tokenize(source));
    }

    private static Optional<JavaTypeKind> declaration(List<String> tokens) {
        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (token.equals(";")) {
                index++;
            } else if (token.equals("package") || token.equals("import")) {
                index = afterStatement(tokens, index);
            } else if (token.equals("@") && is(tokens, index + 1, "interface")) {
                return Optional.of(JavaTypeKind.ANNOTATION);
            } else if (token.equals("@")) {
                index = afterAnnotation(tokens, index);
            } else if (MODIFIERS.contains(token)) {
                index++;
            } else if (token.equals("non") && is(tokens, index + 1, "-") && is(tokens, index + 2, "sealed")) {
                index += 3;
            } else {
                return keyword(tokens, index);
            }
            if (index < 0) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<JavaTypeKind> keyword(List<String> tokens, int index) {
        return switch (tokens.get(index)) {
            case "class" -> Optional.of(JavaTypeKind.CLASS);
            case "interface" -> Optional.of(JavaTypeKind.INTERFACE);
            case "enum" -> Optional.of(JavaTypeKind.ENUM);
            case "record" -> isName(tokens, index + 1) ? Optional.of(JavaTypeKind.RECORD) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static int afterStatement(List<String> tokens, int index) {
        for (int next = index + 1; next < tokens.size(); next++) {
            if (tokens.get(next).equals(";")) {
                return next + 1;
            }
        }
        return -1;
    }

    private static int afterAnnotation(List<String> tokens, int at) {
        int index = at + 1;
        if (!isName(tokens, index)) {
            return -1;
        }
        while (index < tokens.size() && (isName(tokens, index) || tokens.get(index).equals("."))) {
            index++;
        }
        if (index >= tokens.size() || !tokens.get(index).equals("(")) {
            return index;
        }
        int depth = 0;
        for (int next = index; next < tokens.size(); next++) {
            String token = tokens.get(next);
            if (token.equals("(")) {
                depth++;
            } else if (token.equals(")") && --depth == 0) {
                return next + 1;
            }
        }
        return -1;
    }

    private static boolean is(List<String> tokens, int index, String expected) {
        return index < tokens.size() && tokens.get(index).equals(expected);
    }

    private static boolean isName(List<String> tokens, int index) {
        return index < tokens.size() && Character.isJavaIdentifierStart(tokens.get(index).charAt(0));
    }

    private static List<String> tokenize(String source) {
        List<String> tokens = new ArrayList<>();
        int length = source.length();
        int index = source.startsWith(BYTE_ORDER_MARK) ? BYTE_ORDER_MARK.length() : 0;
        while (index < length) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (source.startsWith("//", index)) {
                index = endOfLine(source, index);
            } else if (source.startsWith("/*", index)) {
                index = endOf(source, "*/", index + 2);
            } else if (source.startsWith(TEXT_BLOCK, index)) {
                index = endOf(source, TEXT_BLOCK, index + TEXT_BLOCK.length());
            } else if (current == '"' || current == '\'') {
                index = endOfLiteral(source, index);
            } else if (Character.isJavaIdentifierStart(current)) {
                int start = index;
                while (index < length && Character.isJavaIdentifierPart(source.charAt(index))) {
                    index++;
                }
                tokens.add(source.substring(start, index));
            } else {
                tokens.add(String.valueOf(current));
                index++;
            }
        }
        return tokens;
    }

    private static int endOfLine(String source, int from) {
        int newline = source.indexOf('\n', from);
        return newline < 0 ? source.length() : newline + 1;
    }

    private static int endOf(String source, String terminator, int from) {
        int end = source.indexOf(terminator, from);
        return end < 0 ? source.length() : end + terminator.length();
    }

    private static int endOfLiteral(String source, int start) {
        char quote = source.charAt(start);
        int index = start + 1;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\\') {
                index += 2;
            } else if (current == quote) {
                return index + 1;
            } else if (current == '\n') {
                return index;
            } else {
                index++;
            }
        }
        return source.length();
    }
}
