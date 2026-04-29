package org.jclaude.cli.render;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight regex-based syntax highlighter for fenced code blocks in the REPL. Supports the
 * languages a coding assistant most often returns: java, kotlin, scala, python, javascript,
 * typescript, rust, go, c/cpp, bash/shell, json, yaml, toml, sql, xml/html, css, markdown,
 * groovy, gradle, dockerfile, makefile, ini.
 *
 * <p>Token grammar per language: comments, strings (double + single + backtick where applicable),
 * numbers, keywords, types, and an "other" catch-all. Each token gets an ANSI color from the
 * supplied {@link AnsiPalette}; non-color palettes pass the body through unchanged.
 *
 * <p>Implementation note: the highlighter tokenizes once (priority-ordered: comments &gt; strings
 * &gt; numbers &gt; keywords &gt; types &gt; identifier) so subsequent passes never need to
 * skip over ANSI escapes — much simpler than chained regex replace passes that have to dodge
 * each other's escape codes.
 */
public final class SyntaxHighlighter {

    private SyntaxHighlighter() {}

    /** Languages with bespoke grammars. Aliases are normalized via {@link #normalize}. */
    private static final Map<String, Grammar> GRAMMARS = build_grammars();

    /**
     * Highlight {@code body} for {@code lang}. Returns the ANSI-colored body when colors are
     * enabled and the language is recognized; otherwise returns {@code body} unchanged.
     */
    public static String highlight(String body, String lang, AnsiPalette palette) {
        if (body == null || body.isEmpty() || palette == null || !palette.colors_enabled()) {
            return body == null ? "" : body;
        }
        Grammar grammar = GRAMMARS.get(normalize(lang));
        if (grammar == null) {
            return body;
        }
        return tokenize_and_color(body, grammar, palette);
    }

    /**
     * Map common aliases ({@code js} → {@code javascript}, {@code sh} → {@code bash}, …) to the
     * canonical grammar key. Returns the empty string when {@code lang} is null/blank.
     */
    static String normalize(String lang) {
        if (lang == null) {
            return "";
        }
        String trimmed = lang.trim().toLowerCase(java.util.Locale.ROOT);
        // Strip metadata after the language token: ```java title=Foo.java → "java"
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            trimmed = trimmed.substring(0, space);
        }
        return switch (trimmed) {
            case "js", "node", "javascript" -> "javascript";
            case "ts", "typescript" -> "typescript";
            case "py", "python", "python3" -> "python";
            case "sh", "shell", "zsh", "bash" -> "bash";
            case "yml", "yaml" -> "yaml";
            case "rs", "rust" -> "rust";
            case "kt", "kotlin", "kts" -> "kotlin";
            case "scala", "sc" -> "scala";
            case "go", "golang" -> "go";
            case "rb", "ruby" -> "ruby";
            case "c", "h" -> "c";
            case "cpp", "c++", "cc", "hpp", "cxx" -> "cpp";
            case "cs", "csharp", "c#" -> "csharp";
            case "html", "htm" -> "html";
            case "xml" -> "xml";
            case "css" -> "css";
            case "sql" -> "sql";
            case "json", "jsonl" -> "json";
            case "toml" -> "toml";
            case "md", "markdown" -> "markdown";
            case "dockerfile" -> "dockerfile";
            case "makefile", "make" -> "makefile";
            case "ini", "properties" -> "ini";
            case "groovy" -> "groovy";
            case "gradle" -> "groovy";
            case "diff", "patch" -> "diff";
            default -> trimmed;
        };
    }

    private record Grammar(
            Pattern line_comment,
            Pattern block_comment,
            Pattern string_literal,
            Pattern number_literal,
            Set<String> keywords,
            Set<String> types,
            Pattern identifier) {}

    private static Map<String, Grammar> build_grammars() {
        Map<String, Grammar> map = new LinkedHashMap<>();

        // ---------- C-family (java, kotlin, scala, javascript, typescript, c, cpp, csharp, go,
        //                      rust, groovy) — most share string + number + comment forms ----------
        Pattern slash_line = Pattern.compile("//[^\\n]*");
        Pattern slash_block = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
        Pattern dq_string = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
        Pattern sq_string = Pattern.compile("'(?:\\\\.|[^'\\\\])*'");
        Pattern bt_string = Pattern.compile("`(?:\\\\.|[^`\\\\])*`");
        Pattern combined_string_dq_sq = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
        Pattern combined_string_dq_sq_bt =
                Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`");
        Pattern number = Pattern.compile("\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*\\.?\\d*(?:[eE][+-]?\\d+)?[fFdDlL]?)\\b");
        Pattern ident = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        Set<String> java_kw = Set.of(
                "abstract",
                "assert",
                "boolean",
                "break",
                "byte",
                "case",
                "catch",
                "char",
                "class",
                "const",
                "continue",
                "default",
                "do",
                "double",
                "else",
                "enum",
                "extends",
                "final",
                "finally",
                "float",
                "for",
                "goto",
                "if",
                "implements",
                "import",
                "instanceof",
                "int",
                "interface",
                "long",
                "native",
                "new",
                "non-sealed",
                "null",
                "package",
                "permits",
                "private",
                "protected",
                "public",
                "record",
                "return",
                "sealed",
                "short",
                "static",
                "strictfp",
                "super",
                "switch",
                "synchronized",
                "this",
                "throw",
                "throws",
                "transient",
                "true",
                "false",
                "try",
                "var",
                "void",
                "volatile",
                "while",
                "yield");
        Set<String> java_types = Set.of(
                "String",
                "Integer",
                "Long",
                "Double",
                "Float",
                "Short",
                "Byte",
                "Boolean",
                "Character",
                "Object",
                "Number",
                "List",
                "Map",
                "Set",
                "Optional",
                "Collection",
                "Iterable",
                "Iterator",
                "ArrayList",
                "HashMap",
                "HashSet",
                "TreeMap",
                "TreeSet",
                "LinkedList",
                "LinkedHashMap",
                "Path",
                "Paths",
                "Files",
                "JsonNode",
                "ObjectNode",
                "ArrayNode",
                "ObjectMapper",
                "Exception",
                "RuntimeException",
                "IOException",
                "Throwable",
                "Error",
                "Class",
                "Override",
                "FunctionalInterface",
                "Deprecated",
                "SuppressWarnings");
        Grammar java_grammar =
                new Grammar(slash_line, slash_block, combined_string_dq_sq, number, java_kw, java_types, ident);
        map.put("java", java_grammar);
        map.put("groovy", java_grammar);
        map.put(
                "kotlin",
                new Grammar(
                        slash_line,
                        slash_block,
                        combined_string_dq_sq_bt,
                        number,
                        Set.of(
                                "abstract",
                                "actual",
                                "annotation",
                                "as",
                                "break",
                                "by",
                                "catch",
                                "class",
                                "companion",
                                "const",
                                "constructor",
                                "continue",
                                "crossinline",
                                "data",
                                "do",
                                "dynamic",
                                "else",
                                "enum",
                                "expect",
                                "external",
                                "false",
                                "final",
                                "finally",
                                "for",
                                "fun",
                                "get",
                                "if",
                                "import",
                                "in",
                                "infix",
                                "init",
                                "inline",
                                "inner",
                                "interface",
                                "internal",
                                "is",
                                "lateinit",
                                "noinline",
                                "null",
                                "object",
                                "open",
                                "operator",
                                "out",
                                "override",
                                "package",
                                "param",
                                "private",
                                "property",
                                "protected",
                                "public",
                                "receiver",
                                "reified",
                                "return",
                                "sealed",
                                "set",
                                "setparam",
                                "super",
                                "suspend",
                                "tailrec",
                                "this",
                                "throw",
                                "true",
                                "try",
                                "typealias",
                                "typeof",
                                "val",
                                "var",
                                "vararg",
                                "when",
                                "where",
                                "while",
                                "yield"),
                        Set.of(
                                "Any",
                                "Unit",
                                "Nothing",
                                "String",
                                "Int",
                                "Long",
                                "Double",
                                "Float",
                                "Short",
                                "Byte",
                                "Boolean",
                                "Char",
                                "Array",
                                "List",
                                "MutableList",
                                "Map",
                                "MutableMap",
                                "Set",
                                "MutableSet"),
                        ident));
        map.put(
                "scala",
                new Grammar(
                        slash_line,
                        slash_block,
                        combined_string_dq_sq_bt,
                        number,
                        Set.of(
                                "abstract",
                                "case",
                                "catch",
                                "class",
                                "def",
                                "do",
                                "else",
                                "extends",
                                "false",
                                "final",
                                "finally",
                                "for",
                                "forSome",
                                "if",
                                "implicit",
                                "import",
                                "lazy",
                                "match",
                                "new",
                                "null",
                                "object",
                                "override",
                                "package",
                                "private",
                                "protected",
                                "return",
                                "sealed",
                                "super",
                                "this",
                                "throw",
                                "trait",
                                "try",
                                "true",
                                "type",
                                "val",
                                "var",
                                "while",
                                "with",
                                "yield",
                                "given",
                                "using",
                                "extension",
                                "enum",
                                "export",
                                "then"),
                        Set.of(
                                "Int", "Long", "Double", "Float", "Short", "Byte", "Boolean", "Char", "String", "Unit",
                                "Any", "AnyRef", "AnyVal", "Nothing", "Null", "Option", "Some", "None", "List", "Seq",
                                "Map", "Set", "Vector", "Array"),
                        ident));
        Set<String> js_kw = Set.of(
                "abstract",
                "as",
                "async",
                "await",
                "break",
                "case",
                "catch",
                "class",
                "const",
                "continue",
                "debugger",
                "default",
                "delete",
                "do",
                "else",
                "enum",
                "export",
                "extends",
                "false",
                "finally",
                "for",
                "from",
                "function",
                "get",
                "if",
                "implements",
                "import",
                "in",
                "instanceof",
                "interface",
                "let",
                "new",
                "null",
                "of",
                "package",
                "private",
                "protected",
                "public",
                "return",
                "set",
                "static",
                "super",
                "switch",
                "this",
                "throw",
                "true",
                "try",
                "type",
                "typeof",
                "undefined",
                "var",
                "void",
                "while",
                "with",
                "yield");
        Grammar js_grammar = new Grammar(
                slash_line,
                slash_block,
                combined_string_dq_sq_bt,
                number,
                js_kw,
                Set.of(
                        "Promise", "Array", "Object", "String", "Number", "Boolean", "Map", "Set", "WeakMap", "WeakSet",
                        "Symbol", "JSON", "Math", "Date", "RegExp", "Error"),
                ident);
        map.put("javascript", js_grammar);
        map.put(
                "typescript",
                new Grammar(
                        slash_line,
                        slash_block,
                        combined_string_dq_sq_bt,
                        number,
                        js_kw,
                        Set.of(
                                "string",
                                "number",
                                "boolean",
                                "any",
                                "void",
                                "unknown",
                                "never",
                                "object",
                                "Array",
                                "Promise",
                                "Record",
                                "Partial",
                                "Readonly",
                                "Pick",
                                "Omit",
                                "Map",
                                "Set"),
                        ident));
        map.put(
                "c",
                new Grammar(
                        slash_line,
                        slash_block,
                        combined_string_dq_sq,
                        number,
                        Set.of(
                                "auto",
                                "break",
                                "case",
                                "char",
                                "const",
                                "continue",
                                "default",
                                "do",
                                "double",
                                "else",
                                "enum",
                                "extern",
                                "float",
                                "for",
                                "goto",
                                "if",
                                "inline",
                                "int",
                                "long",
                                "register",
                                "restrict",
                                "return",
                                "short",
                                "signed",
                                "sizeof",
                                "static",
                                "struct",
                                "switch",
                                "typedef",
                                "union",
                                "unsigned",
                                "void",
                                "volatile",
                                "while",
                                "_Bool",
                                "_Atomic"),
                        Set.of(
                                "size_t",
                                "ptrdiff_t",
                                "uint8_t",
                                "uint16_t",
                                "uint32_t",
                                "uint64_t",
                                "int8_t",
                                "int16_t",
                                "int32_t",
                                "int64_t",
                                "FILE"),
                        ident));
        map.put(
                "cpp",
                new Grammar(
                        slash_line,
                        slash_block,
                        combined_string_dq_sq,
                        number,
                        Set.of(
                                "auto",
                                "bool",
                                "break",
                                "case",
                                "catch",
                                "char",
                                "class",
                                "const",
                                "constexpr",
                                "continue",
                                "default",
                                "delete",
                                "do",
                                "double",
                                "else",
                                "enum",
                                "explicit",
                                "export",
                                "extern",
                                "false",
                                "float",
                                "for",
                                "friend",
                                "goto",
                                "if",
                                "inline",
                                "int",
                                "long",
                                "mutable",
                                "namespace",
                                "new",
                                "noexcept",
                                "nullptr",
                                "operator",
                                "private",
                                "protected",
                                "public",
                                "register",
                                "return",
                                "short",
                                "signed",
                                "sizeof",
                                "static",
                                "static_cast",
                                "dynamic_cast",
                                "reinterpret_cast",
                                "const_cast",
                                "struct",
                                "switch",
                                "template",
                                "this",
                                "throw",
                                "true",
                                "try",
                                "typedef",
                                "typeid",
                                "typename",
                                "union",
                                "unsigned",
                                "using",
                                "virtual",
                                "void",
                                "volatile",
                                "while"),
                        Set.of(
                                "std",
                                "string",
                                "vector",
                                "map",
                                "set",
                                "unordered_map",
                                "unordered_set",
                                "list",
                                "deque",
                                "array",
                                "tuple",
                                "pair",
                                "shared_ptr",
                                "unique_ptr",
                                "weak_ptr"),
                        ident));
        map.put(
                "csharp",
                new Grammar(
                        slash_line,
                        slash_block,
                        combined_string_dq_sq,
                        number,
                        Set.of(
                                "abstract",
                                "as",
                                "async",
                                "await",
                                "base",
                                "bool",
                                "break",
                                "byte",
                                "case",
                                "catch",
                                "char",
                                "checked",
                                "class",
                                "const",
                                "continue",
                                "decimal",
                                "default",
                                "delegate",
                                "do",
                                "double",
                                "else",
                                "enum",
                                "event",
                                "explicit",
                                "extern",
                                "false",
                                "finally",
                                "fixed",
                                "float",
                                "for",
                                "foreach",
                                "goto",
                                "if",
                                "implicit",
                                "in",
                                "int",
                                "interface",
                                "internal",
                                "is",
                                "lock",
                                "long",
                                "namespace",
                                "new",
                                "null",
                                "object",
                                "operator",
                                "out",
                                "override",
                                "params",
                                "private",
                                "protected",
                                "public",
                                "readonly",
                                "ref",
                                "return",
                                "sbyte",
                                "sealed",
                                "short",
                                "sizeof",
                                "stackalloc",
                                "static",
                                "string",
                                "struct",
                                "switch",
                                "this",
                                "throw",
                                "true",
                                "try",
                                "typeof",
                                "uint",
                                "ulong",
                                "unchecked",
                                "unsafe",
                                "ushort",
                                "using",
                                "var",
                                "virtual",
                                "void",
                                "volatile",
                                "while",
                                "yield",
                                "record",
                                "init"),
                        Set.of(
                                "Task",
                                "List",
                                "Dictionary",
                                "IEnumerable",
                                "Action",
                                "Func",
                                "String",
                                "Int32",
                                "Object"),
                        ident));
        map.put(
                "go",
                new Grammar(
                        slash_line,
                        slash_block,
                        combined_string_dq_sq_bt,
                        number,
                        Set.of(
                                "break",
                                "case",
                                "chan",
                                "const",
                                "continue",
                                "default",
                                "defer",
                                "else",
                                "fallthrough",
                                "for",
                                "func",
                                "go",
                                "goto",
                                "if",
                                "import",
                                "interface",
                                "map",
                                "package",
                                "range",
                                "return",
                                "select",
                                "struct",
                                "switch",
                                "type",
                                "var",
                                "true",
                                "false",
                                "nil"),
                        Set.of(
                                "string",
                                "int",
                                "int8",
                                "int16",
                                "int32",
                                "int64",
                                "uint",
                                "uint8",
                                "uint16",
                                "uint32",
                                "uint64",
                                "uintptr",
                                "byte",
                                "rune",
                                "float32",
                                "float64",
                                "complex64",
                                "complex128",
                                "bool",
                                "error",
                                "any"),
                        ident));
        map.put(
                "rust",
                new Grammar(
                        slash_line,
                        slash_block,
                        combined_string_dq_sq,
                        number,
                        Set.of(
                                "as",
                                "async",
                                "await",
                                "break",
                                "const",
                                "continue",
                                "crate",
                                "dyn",
                                "else",
                                "enum",
                                "extern",
                                "false",
                                "fn",
                                "for",
                                "if",
                                "impl",
                                "in",
                                "let",
                                "loop",
                                "match",
                                "mod",
                                "move",
                                "mut",
                                "pub",
                                "ref",
                                "return",
                                "self",
                                "Self",
                                "static",
                                "struct",
                                "super",
                                "trait",
                                "true",
                                "type",
                                "unsafe",
                                "use",
                                "where",
                                "while",
                                "yield"),
                        Set.of(
                                "String",
                                "str",
                                "Vec",
                                "Option",
                                "Result",
                                "Box",
                                "Rc",
                                "Arc",
                                "Mutex",
                                "RwLock",
                                "HashMap",
                                "HashSet",
                                "BTreeMap",
                                "BTreeSet",
                                "i8",
                                "i16",
                                "i32",
                                "i64",
                                "i128",
                                "isize",
                                "u8",
                                "u16",
                                "u32",
                                "u64",
                                "u128",
                                "usize",
                                "f32",
                                "f64",
                                "bool",
                                "char"),
                        ident));

        // ---------- # comment family ----------
        Pattern hash_line = Pattern.compile("#[^\\n]*");
        map.put(
                "python",
                new Grammar(
                        hash_line,
                        null,
                        combined_string_dq_sq,
                        number,
                        Set.of(
                                "False",
                                "None",
                                "True",
                                "and",
                                "as",
                                "assert",
                                "async",
                                "await",
                                "break",
                                "class",
                                "continue",
                                "def",
                                "del",
                                "elif",
                                "else",
                                "except",
                                "finally",
                                "for",
                                "from",
                                "global",
                                "if",
                                "import",
                                "in",
                                "is",
                                "lambda",
                                "nonlocal",
                                "not",
                                "or",
                                "pass",
                                "raise",
                                "return",
                                "try",
                                "while",
                                "with",
                                "yield",
                                "match",
                                "case"),
                        Set.of(
                                "int",
                                "float",
                                "str",
                                "bytes",
                                "list",
                                "tuple",
                                "dict",
                                "set",
                                "frozenset",
                                "bool",
                                "object",
                                "type",
                                "Exception",
                                "ValueError",
                                "TypeError",
                                "KeyError",
                                "IndexError"),
                        ident));
        map.put(
                "ruby",
                new Grammar(
                        hash_line,
                        null,
                        combined_string_dq_sq,
                        number,
                        Set.of(
                                "BEGIN",
                                "END",
                                "alias",
                                "and",
                                "begin",
                                "break",
                                "case",
                                "class",
                                "def",
                                "defined?",
                                "do",
                                "else",
                                "elsif",
                                "end",
                                "ensure",
                                "false",
                                "for",
                                "if",
                                "in",
                                "module",
                                "next",
                                "nil",
                                "not",
                                "or",
                                "redo",
                                "rescue",
                                "retry",
                                "return",
                                "self",
                                "super",
                                "then",
                                "true",
                                "undef",
                                "unless",
                                "until",
                                "when",
                                "while",
                                "yield"),
                        Set.of("String", "Integer", "Float", "Array", "Hash", "Symbol", "Object", "Class", "Module"),
                        ident));
        map.put(
                "bash",
                new Grammar(
                        hash_line,
                        null,
                        combined_string_dq_sq_bt,
                        number,
                        Set.of(
                                "alias",
                                "bg",
                                "bind",
                                "break",
                                "builtin",
                                "case",
                                "cd",
                                "command",
                                "compgen",
                                "complete",
                                "continue",
                                "declare",
                                "do",
                                "done",
                                "echo",
                                "elif",
                                "else",
                                "esac",
                                "eval",
                                "exec",
                                "exit",
                                "export",
                                "fc",
                                "fg",
                                "fi",
                                "for",
                                "function",
                                "getopts",
                                "hash",
                                "help",
                                "history",
                                "if",
                                "in",
                                "jobs",
                                "kill",
                                "let",
                                "local",
                                "logout",
                                "popd",
                                "pushd",
                                "pwd",
                                "read",
                                "readonly",
                                "return",
                                "select",
                                "set",
                                "shift",
                                "shopt",
                                "source",
                                "suspend",
                                "test",
                                "then",
                                "time",
                                "times",
                                "trap",
                                "type",
                                "typeset",
                                "ulimit",
                                "umask",
                                "unalias",
                                "unset",
                                "until",
                                "wait",
                                "while"),
                        Set.of(),
                        ident));
        map.put(
                "yaml",
                new Grammar(
                        hash_line,
                        null,
                        combined_string_dq_sq,
                        number,
                        Set.of("true", "false", "yes", "no", "on", "off", "null", "~"),
                        Set.of(),
                        ident));
        map.put(
                "toml",
                new Grammar(hash_line, null, combined_string_dq_sq, number, Set.of("true", "false"), Set.of(), ident));
        map.put(
                "ini",
                new Grammar(hash_line, null, combined_string_dq_sq, number, Set.of("true", "false"), Set.of(), ident));
        map.put(
                "dockerfile",
                new Grammar(
                        hash_line,
                        null,
                        combined_string_dq_sq,
                        number,
                        Set.of(
                                "FROM",
                                "RUN",
                                "CMD",
                                "LABEL",
                                "MAINTAINER",
                                "EXPOSE",
                                "ENV",
                                "ADD",
                                "COPY",
                                "ENTRYPOINT",
                                "VOLUME",
                                "USER",
                                "WORKDIR",
                                "ARG",
                                "ONBUILD",
                                "STOPSIGNAL",
                                "HEALTHCHECK",
                                "SHELL"),
                        Set.of(),
                        ident));
        map.put(
                "makefile",
                new Grammar(
                        hash_line,
                        null,
                        combined_string_dq_sq,
                        number,
                        Set.of(
                                "ifeq",
                                "ifneq",
                                "ifdef",
                                "ifndef",
                                "else",
                                "endif",
                                "include",
                                "define",
                                "endef",
                                "export",
                                "unexport",
                                "vpath"),
                        Set.of(),
                        ident));

        // ---------- SQL ----------
        Pattern sql_line = Pattern.compile("--[^\\n]*");
        map.put(
                "sql",
                new Grammar(
                        sql_line,
                        slash_block,
                        combined_string_dq_sq,
                        number,
                        Set.of(
                                "SELECT",
                                "FROM",
                                "WHERE",
                                "GROUP",
                                "HAVING",
                                "ORDER",
                                "BY",
                                "ASC",
                                "DESC",
                                "LIMIT",
                                "OFFSET",
                                "INSERT",
                                "INTO",
                                "VALUES",
                                "UPDATE",
                                "SET",
                                "DELETE",
                                "CREATE",
                                "DROP",
                                "ALTER",
                                "TABLE",
                                "INDEX",
                                "VIEW",
                                "TRIGGER",
                                "PROCEDURE",
                                "FUNCTION",
                                "AS",
                                "AND",
                                "OR",
                                "NOT",
                                "NULL",
                                "IS",
                                "IN",
                                "LIKE",
                                "BETWEEN",
                                "JOIN",
                                "INNER",
                                "LEFT",
                                "RIGHT",
                                "FULL",
                                "OUTER",
                                "ON",
                                "UNION",
                                "ALL",
                                "DISTINCT",
                                "WITH",
                                "CASE",
                                "WHEN",
                                "THEN",
                                "ELSE",
                                "END",
                                "PRIMARY",
                                "KEY",
                                "FOREIGN",
                                "REFERENCES",
                                "DEFAULT",
                                "UNIQUE",
                                "CHECK",
                                "TRUE",
                                "FALSE",
                                "BEGIN",
                                "COMMIT",
                                "ROLLBACK",
                                "TRANSACTION"),
                        Set.of(
                                "INT",
                                "INTEGER",
                                "BIGINT",
                                "SMALLINT",
                                "TINYINT",
                                "FLOAT",
                                "DOUBLE",
                                "DECIMAL",
                                "NUMERIC",
                                "VARCHAR",
                                "CHAR",
                                "TEXT",
                                "BLOB",
                                "DATE",
                                "TIME",
                                "TIMESTAMP",
                                "DATETIME",
                                "BOOLEAN",
                                "BOOL"),
                        ident));

        // ---------- JSON / XML / HTML / CSS / MARKDOWN / DIFF ----------
        map.put(
                "json",
                new Grammar(
                        null, null, combined_string_dq_sq, number, Set.of("true", "false", "null"), Set.of(), ident));
        map.put(
                "xml",
                new Grammar(
                        null,
                        Pattern.compile("<!--.*?-->", Pattern.DOTALL),
                        combined_string_dq_sq,
                        number,
                        Set.of(),
                        Set.of(),
                        ident));
        map.put(
                "html",
                new Grammar(
                        null,
                        Pattern.compile("<!--.*?-->", Pattern.DOTALL),
                        combined_string_dq_sq,
                        number,
                        Set.of(),
                        Set.of(),
                        ident));
        map.put(
                "css",
                new Grammar(null, slash_block, combined_string_dq_sq, number, Set.of("important"), Set.of(), ident));
        map.put("markdown", new Grammar(null, null, null, null, Set.of(), Set.of(), ident));
        map.put("diff", new Grammar(null, null, null, null, Set.of(), Set.of(), ident));
        return map;
    }

    /**
     * Tokenize the body in priority order — comments &gt; strings &gt; numbers &gt; keywords/types
     * &gt; everything else — appending the colored output as we go. Once we paint a span we never
     * re-scan it, so palette ANSI escapes can't collide with each other.
     */
    private static String tokenize_and_color(String body, Grammar grammar, AnsiPalette palette) {
        StringBuilder out = new StringBuilder(body.length() * 2);
        int cursor = 0;
        int len = body.length();
        while (cursor < len) {
            int start = cursor;
            // 1. block comment
            if (grammar.block_comment() != null) {
                Matcher m = grammar.block_comment().matcher(body).region(cursor, len);
                if (m.lookingAt()) {
                    out.append(palette.code_comment(m.group()));
                    cursor = m.end();
                    continue;
                }
            }
            // 2. line comment (consume up to and including the newline so the dim color stops at EOL)
            if (grammar.line_comment() != null) {
                Matcher m = grammar.line_comment().matcher(body).region(cursor, len);
                if (m.lookingAt()) {
                    out.append(palette.code_comment(m.group()));
                    cursor = m.end();
                    continue;
                }
            }
            // 3. string literal
            if (grammar.string_literal() != null) {
                Matcher m = grammar.string_literal().matcher(body).region(cursor, len);
                if (m.lookingAt()) {
                    out.append(palette.code_string(m.group()));
                    cursor = m.end();
                    continue;
                }
            }
            // 4. number literal
            if (grammar.number_literal() != null) {
                Matcher m = grammar.number_literal().matcher(body).region(cursor, len);
                if (m.lookingAt()) {
                    out.append(palette.code_number(m.group()));
                    cursor = m.end();
                    continue;
                }
            }
            // 5. identifier (may be a keyword / type)
            if (grammar.identifier() != null) {
                Matcher m = grammar.identifier().matcher(body).region(cursor, len);
                if (m.lookingAt()) {
                    String word = m.group();
                    if (grammar.keywords().contains(word)) {
                        out.append(palette.code_keyword(word));
                    } else if (grammar.types().contains(word)) {
                        out.append(palette.code_type(word));
                    } else {
                        out.append(word);
                    }
                    cursor = m.end();
                    continue;
                }
            }
            // 6. fallthrough: copy one character verbatim
            out.append(body.charAt(cursor));
            cursor++;
            if (cursor == start) {
                // Defensive: regex matched empty — advance to avoid infinite loop.
                cursor = start + 1;
            }
        }
        return out.toString();
    }
}
