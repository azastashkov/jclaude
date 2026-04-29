package org.jclaude.cli.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies {@link SyntaxHighlighter} colors common-language tokens via {@link AnsiPalette}. */
final class SyntaxHighlighterTest {

    private static final AnsiPalette COLOR = AnsiPalette.with_color_enabled();
    private static final AnsiPalette PLAIN = AnsiPalette.with_color_disabled();

    @Test
    void colors_disabled_passes_body_through_unchanged() {
        String body = "public class Foo {}";
        assertThat(SyntaxHighlighter.highlight(body, "java", PLAIN)).isEqualTo(body);
    }

    @Test
    void unknown_language_passes_body_through_unchanged() {
        String body = "lorem ipsum dolor sit amet";
        assertThat(SyntaxHighlighter.highlight(body, "unicornscript", COLOR)).isEqualTo(body);
    }

    @Test
    void java_keywords_and_strings_are_colored_with_islands_dark() {
        String out = SyntaxHighlighter.highlight("public String name = \"hi\";", "java", COLOR);
        // Islands Dark: keywords orange, types yellow, strings green, identifier 'name' uncolored.
        assertThat(out).contains(AnsiPalette.ISLANDS_KEYWORD + "public");
        assertThat(out).contains(AnsiPalette.ISLANDS_TYPE + "String");
        assertThat(out).contains(AnsiPalette.ISLANDS_STRING + "\"hi\"");
        assertThat(out).contains("name");
    }

    @Test
    void java_line_comments_are_dimmed_islands_grey() {
        String out = SyntaxHighlighter.highlight("int x = 1; // count\n", "java", COLOR);
        assertThat(out).contains(AnsiPalette.ISLANDS_COMMENT + "// count");
        assertThat(out).contains(AnsiPalette.ISLANDS_NUMBER + "1");
    }

    @Test
    void java_block_comments_span_multiple_lines() {
        String out = SyntaxHighlighter.highlight("/* multi\nline */ int x;", "java", COLOR);
        assertThat(out).contains(AnsiPalette.ISLANDS_COMMENT + "/* multi\nline */");
    }

    @Test
    void json_treats_true_false_null_as_keywords() {
        String out = SyntaxHighlighter.highlight("{\"a\":true, \"b\":null, \"c\":42}", "json", COLOR);
        assertThat(out).contains(AnsiPalette.ISLANDS_STRING + "\"a\"");
        assertThat(out).contains(AnsiPalette.ISLANDS_KEYWORD + "true");
        assertThat(out).contains(AnsiPalette.ISLANDS_KEYWORD + "null");
        assertThat(out).contains(AnsiPalette.ISLANDS_NUMBER + "42");
    }

    @Test
    void bash_hash_comments_and_strings_color() {
        String out = SyntaxHighlighter.highlight("echo \"hi\" # greet", "bash", COLOR);
        assertThat(out).contains(AnsiPalette.ISLANDS_KEYWORD + "echo");
        assertThat(out).contains(AnsiPalette.ISLANDS_STRING + "\"hi\"");
        assertThat(out).contains(AnsiPalette.ISLANDS_COMMENT + "# greet");
    }

    @Test
    void python_def_and_string_color() {
        String out = SyntaxHighlighter.highlight("def greet(name): return \"hi\"", "python", COLOR);
        assertThat(out).contains(AnsiPalette.ISLANDS_KEYWORD + "def");
        assertThat(out).contains(AnsiPalette.ISLANDS_KEYWORD + "return");
        assertThat(out).contains(AnsiPalette.ISLANDS_STRING + "\"hi\"");
    }

    @Test
    void rust_keywords_and_types_color() {
        String out = SyntaxHighlighter.highlight("let v: Vec<i32> = Vec::new();", "rust", COLOR);
        assertThat(out).contains(AnsiPalette.ISLANDS_KEYWORD + "let");
        assertThat(out).contains(AnsiPalette.ISLANDS_TYPE + "Vec");
        assertThat(out).contains(AnsiPalette.ISLANDS_TYPE + "i32");
    }

    @Test
    void typescript_uses_javascript_keywords_with_typescript_types() {
        String out = SyntaxHighlighter.highlight("const x: string = `hello`;", "typescript", COLOR);
        assertThat(out).contains(AnsiPalette.ISLANDS_KEYWORD + "const");
        assertThat(out).contains(AnsiPalette.ISLANDS_TYPE + "string");
        assertThat(out).contains(AnsiPalette.ISLANDS_STRING + "`hello`");
    }

    @Test
    void normalize_maps_aliases_to_canonical_grammar() {
        // sh / zsh / shell all share the bash grammar.
        String body = "echo hi";
        assertThat(SyntaxHighlighter.highlight(body, "sh", COLOR)).contains(AnsiPalette.ISLANDS_KEYWORD + "echo");
        assertThat(SyntaxHighlighter.highlight(body, "zsh", COLOR)).contains(AnsiPalette.ISLANDS_KEYWORD + "echo");
        assertThat(SyntaxHighlighter.highlight(body, "shell", COLOR)).contains(AnsiPalette.ISLANDS_KEYWORD + "echo");
        // py / python3 share python.
        assertThat(SyntaxHighlighter.highlight("def f(): pass", "py", COLOR))
                .contains(AnsiPalette.ISLANDS_KEYWORD + "def");
        assertThat(SyntaxHighlighter.highlight("def f(): pass", "python3", COLOR))
                .contains(AnsiPalette.ISLANDS_KEYWORD + "def");
        // js -> javascript; ts -> typescript.
        assertThat(SyntaxHighlighter.highlight("const x = 1", "js", COLOR))
                .contains(AnsiPalette.ISLANDS_KEYWORD + "const");
        assertThat(SyntaxHighlighter.highlight("const x: number = 1", "ts", COLOR))
                .contains(AnsiPalette.ISLANDS_TYPE + "number");
    }

    @Test
    void empty_or_null_body_is_safe() {
        assertThat(SyntaxHighlighter.highlight(null, "java", COLOR)).isEmpty();
        assertThat(SyntaxHighlighter.highlight("", "java", COLOR)).isEmpty();
    }

    @Test
    void info_string_with_metadata_after_the_language_token_still_normalizes() {
        // ```java title=Foo.java → "java"
        String out = SyntaxHighlighter.highlight("class X {}", "java title=Foo.java", COLOR);
        assertThat(out).contains(AnsiPalette.ISLANDS_KEYWORD + "class");
    }
}
