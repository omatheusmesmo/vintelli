package dev.omatheusmesmo.vintelli.tui.symbol;

/**
 * The LSP symbol kind numbers the symbol overlays name and filter. Go to Class lists the types: classes, interfaces,
 * enums and structs; Go to Symbol lists every kind.
 */
final class SymbolKinds {

    static final int CLASS = 5;
    static final int ENUM = 10;
    static final int INTERFACE = 11;
    static final int STRUCT = 23;

    private static final String[] NAMES = {"symbol", "file", "module", "namespace", "package", "class", "method",
            "property", "field", "constructor", "enum", "interface", "function", "variable", "constant", "string",
            "number", "boolean", "array", "object", "key", "null", "enum member", "struct", "event", "operator",
            "type parameter"};

    private SymbolKinds() {
    }

    static String label(int kind) {
        return kind > 0 && kind < NAMES.length ? NAMES[kind] : NAMES[0];
    }

    static boolean isType(int kind) {
        return kind == CLASS || kind == INTERFACE || kind == ENUM || kind == STRUCT;
    }
}
