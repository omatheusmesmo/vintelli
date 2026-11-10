package dev.omatheusmesmo.vintelli.tui.icons;

/**
 * The kind of the first top-level type declared in a Java source file. An
 * abstract class is a {@link #CLASS}: the abstract, sealed, final and test
 * markers are not icons of their own.
 */
public enum JavaTypeKind {
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION
}
