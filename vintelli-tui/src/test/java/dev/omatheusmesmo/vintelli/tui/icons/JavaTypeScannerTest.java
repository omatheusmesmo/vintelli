package dev.omatheusmesmo.vintelli.tui.icons;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaTypeScannerTest {

    @Test
    void classAbstractClassAndModifiedClassAreClasses() {
        assertKind(JavaTypeKind.CLASS, "public class A {}");
        assertKind(JavaTypeKind.CLASS, "public abstract class A {}");
        assertKind(JavaTypeKind.CLASS, "public final class A {}");
        assertKind(JavaTypeKind.CLASS, "sealed abstract class A permits B {}");
        assertKind(JavaTypeKind.CLASS, "public non-sealed class A extends B {}");
    }

    @Test
    void interfaceIncludingSealedInterfaceIsAnInterface() {
        assertKind(JavaTypeKind.INTERFACE, "public interface A {}");
        assertKind(JavaTypeKind.INTERFACE, "public sealed interface A permits B, C {}");
    }

    @Test
    void enumIsAnEnum() {
        assertKind(JavaTypeKind.ENUM, "public enum Level { LOW, HIGH }");
    }

    @Test
    void recordIsARecord() {
        assertKind(JavaTypeKind.RECORD, "public record Point(int x, int y) {}");
        assertKind(JavaTypeKind.RECORD, "record Point<T>(T value) {}");
    }

    @Test
    void annotationDeclarationIsAnAnnotation() {
        assertKind(JavaTypeKind.ANNOTATION, "public @interface Marker {}");
        assertKind(JavaTypeKind.ANNOTATION, "@Documented\n@Retention(RetentionPolicy.RUNTIME)\n@interface Marker {}");
    }

    @Test
    void annotationsBeforeTheDeclarationAreSkipped() {
        assertKind(JavaTypeKind.INTERFACE, """
                @Deprecated(since = "1.0")
                @SuppressWarnings({"unchecked", "rawtypes"})
                @jakarta.annotation.Generated("tool")
                public interface Api {}
                """);
    }

    @Test
    void classReferencesInsideAnnotationArgumentsDoNotCountAsDeclarations() {
        assertKind(JavaTypeKind.CLASS, """
                @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, defaultImpl = Fallback.class)
                public class Payload {}
                """);
    }

    @Test
    void packageImportAndHeaderCommentsAreSkipped() {
        assertKind(JavaTypeKind.ENUM, """
                /*
                 * Copyright notice: this interface belongs to the example.
                 */
                package dev.example.model;

                import java.util.List;
                import static java.util.Objects.requireNonNull;

                /** Javadoc about the enum class. */
                public enum Mode { ON, OFF }
                """);
    }

    @Test
    void wordsInCommentsDoNotConfuseTheScan() {
        assertKind(JavaTypeKind.RECORD, """
                // class Fake {}
                /* interface Fake {} */
                /** enum Fake for the record below */
                public record Real() {}
                """);
    }

    @Test
    void wordsInStringAndCharacterLiteralsDoNotConfuseTheScan() {
        assertKind(JavaTypeKind.ENUM, """
                package a;
                @Named("class interface record")
                @Label('x')
                public enum Level { A }
                """);
        assertKind(JavaTypeKind.INTERFACE, """
                @Doc(\"\"\"
                    class Fake {}
                    \"\"\")
                public interface Api {}
                """);
    }

    @Test
    void escapedQuotesInsideLiteralsStayInsideTheLiteral() {
        assertKind(JavaTypeKind.CLASS, "@Named(\"say \\\"interface\\\"\")\npublic class A {}");
    }

    @Test
    void byteOrderMarkIsIgnored() {
        assertKind(JavaTypeKind.CLASS, "﻿public class A {}");
    }

    @Test
    void fileWithoutTypeHasNoKind() {
        assertKind(null, "");
        assertKind(null, "package dev.example;\nimport java.util.List;\n");
        assertKind(null, "// only a comment, class Fake {}\n");
        assertKind(null, "module dev.example { requires java.base; }");
    }

    @Test
    void declarationAfterAFieldOrMethodIsNotAType() {
        assertKind(null, "int counter;\nclass Late {}");
        assertKind(null, "System.out.println(\"interface\");");
    }

    @Test
    void wordClassInPlainTextIsNotADeclaration() {
        assertKind(null, "This class does things and is not Java.");
    }

    @Test
    void recordWithoutANameIsNotARecord() {
        assertKind(null, "record");
    }

    @Test
    void unterminatedCommentOrAnnotationHasNoKind() {
        assertKind(null, "/* never closed class A {}");
        assertKind(null, "@Named(\"x\"\npublic class A {}");
    }

    private static void assertKind(JavaTypeKind expected, String source) {
        Optional<JavaTypeKind> actual = JavaTypeScanner.scan(source);

        assertEquals(Optional.ofNullable(expected), actual, source);
    }
}
