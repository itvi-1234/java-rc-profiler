package io.runtimeconditions.profiler.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.runtimeconditions.profiler.bindings.Binding;
import io.runtimeconditions.profiler.bindings.Catalog;
import io.runtimeconditions.profiler.core.Json;
import io.runtimeconditions.profiler.core.ProfilerException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Reads declarations made through generated binding packages from Java source. The source is parsed, never
 * compiled or run: names are resolved from imports against the verified manifests, and every value must be
 * statically determinable.
 */
public final class Extractor {
    private static final String STAGE = "extract";
    private static final Set<String> SKIPPED = Set.of("target", "build", "out", "node_modules", "bin", "dist", "coverage");

    public record Extraction(List<ObjectNode> conditions, List<Binding> used) {
    }

    /** A type of a binding package named in source: the top-level name and any nested names after it. */
    private record TypeName(Binding binding, String name, List<String> rest) {
    }

    private record Expected(Binding binding, JsonNode ref) {
    }

    private record Instance(Binding binding, JsonNode entry, JsonNode value) {
    }

    private record Constant(ExpressionTree node, SourceFile source, TreePath scope) {
    }

    private final Path root;
    private final Catalog catalog;
    private final Map<Path, SourceFile> sources = new LinkedHashMap<>();
    private final Map<String, ClassTree> classes = new LinkedHashMap<>();
    private final Map<String, SourceFile> classFiles = new LinkedHashMap<>();
    private final Map<ClassTree, TreePath> classPaths = new LinkedHashMap<>();
    private final Map<Path, TreePath> unitPaths = new LinkedHashMap<>();
    private final Map<String, Binding> used = new LinkedHashMap<>();
    private final List<ObjectNode> conditions = new ArrayList<>();
    private Trees trees;

    public Extractor(Path root, Catalog catalog) {
        this.root = root.toAbsolutePath().normalize();
        this.catalog = catalog;
    }

    /** The Java files that make up the project: everything outside build output, hidden directories and tests. */
    public static List<Path> projectFiles(Path root) {
        List<Path> found = new ArrayList<>();
        Path base = root.toAbsolutePath().normalize();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(base)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    if (attrs.isSymbolicLink() || SKIPPED.contains(name) || name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Path relative = base.relativize(dir);
                    if (relative.endsWith(Path.of("src", "test"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !attrs.isSymbolicLink() && file.getFileName().toString().endsWith(".java")
                            && !file.getFileName().toString().equals("module-info.java")
                            && !file.getFileName().toString().equals("package-info.java")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ProfilerException(STAGE, "cannot read the project: " + e.getMessage(), base.toString());
        }
        found.sort(null);
        return found;
    }

    /** Parses all files so that constants can be followed between them. */
    private void parse(List<Path> files) {
        if (!sources.isEmpty() || files.isEmpty()) {
            return;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ProfilerException(STAGE, "the profiler needs a JDK, not a JRE, to read Java source");
        }
        for (Path file : files) {
            try {
                if (Files.size(file) > Json.MAX_BYTES) {
                    throw new ProfilerException(STAGE, "file exceeds the " + Json.MAX_BYTES + " byte size limit", relative(file));
                }
            } catch (IOException e) {
                throw new ProfilerException(STAGE, "cannot read the file: " + e.getMessage(), relative(file));
            }
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(files));
            trees = Trees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                Path path = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                SourceFile source = new SourceFile(path, relative(path), unit, trees.getSourcePositions());
                sources.put(path, source);
                unitPaths.put(path, new TreePath(unit));
                for (ClassTree type : source.topLevelClasses()) {
                    index(source, type, source.packageName.isEmpty() ? type.getSimpleName().toString()
                            : source.packageName + "." + type.getSimpleName(), new TreePath(new TreePath(unit), type));
                }
            }
        } catch (IOException e) {
            throw new ProfilerException(STAGE, "cannot read the project: " + e.getMessage());
        }
        for (Diagnostic<? extends JavaFileObject> item : diagnostics.getDiagnostics()) {
            if (item.getKind() == Diagnostic.Kind.ERROR) {
                String file = item.getSource() == null ? "" : relative(Path.of(item.getSource().toUri()));
                throw new ProfilerException(STAGE, "syntax error: " + item.getMessage(null), file + ":" + item.getLineNumber() + ":" + item.getColumnNumber());
            }
        }
    }

    private void index(SourceFile source, ClassTree type, String name, TreePath path) {
        classes.put(name, type);
        classFiles.put(name, source);
        classPaths.put(type, path);
        for (Tree member : type.getMembers()) {
            if (member instanceof ClassTree nested) {
                index(source, nested, name + "." + nested.getSimpleName(), new TreePath(path, nested));
            }
        }
    }

    private String relative(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute).toString().replace('\\', '/');
    }

    /** The binding packages the project's sources import, for verify-bindings. */
    public List<Binding> discover(List<Path> files) {
        parse(files);
        Map<String, Binding> found = new LinkedHashMap<>();
        for (SourceFile source : sources.values()) {
            for (SourceFile.Import item : source.imports) {
                // a.b.Type or a.b.* names the package a.b; a static import a.b.Type.member or a.b.Type.* names a.b.Type.
                Binding binding = catalog.byJavaPackage(item.qualifier());
                if (binding == null && item.isStatic()) {
                    binding = catalog.byJavaPackage(parent(item.qualifier()));
                }
                if (binding != null) {
                    found.put(binding.root().toString(), binding);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private static String parent(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    public Extraction run(List<Path> files) {
        parse(files);
        for (SourceFile source : sources.values()) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    Resolved target = declaration(node, source);
                    if (target != null) {
                        conditions.add(condition(node, target, source, getCurrentPath()));
                    }
                    return super.visitMethodInvocation(node, unused);
                }
            }.scan(unitPaths.get(source.path), null);
        }
        return new Extraction(conditions, new ArrayList<>(used.values()));
    }

    // ---- name resolution -------------------------------------------------------------------------------

    private record Resolved(Binding binding, Binding.Declaration declaration) {
    }

    /** Returns the generated declaration function the call invokes, or null when it is some other call. */
    private Resolved declaration(MethodInvocationTree call, SourceFile source) {
        ExpressionTree select = call.getMethodSelect();
        if (select instanceof IdentifierTree id) {
            String method = id.getName().toString();
            for (SourceFile.Import item : source.imports) {
                if (!item.isStatic()) {
                    continue;
                }
                if ((!item.onDemand() && item.name().equals(method)) || item.onDemand()) {
                    Resolved found = function(item.qualifier(), method);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }
        if (select instanceof MemberSelectTree member) {
            String owner = SourceFile.dotted(member.getExpression());
            if (owner == null) {
                return null;
            }
            TypeName type = typeName(source, owner);
            if (type == null || !type.rest().isEmpty()) {
                return null;
            }
            return function(type.binding().javaPackage() + "." + type.name(), member.getIdentifier().toString());
        }
        return null;
    }

    private Resolved function(String qualifiedClass, String method) {
        String pkg = parent(qualifiedClass);
        Binding binding = catalog.byJavaPackage(pkg);
        if (binding == null) {
            return null;
        }
        String className = qualifiedClass.substring(pkg.length() + 1);
        for (Binding.Declaration item : binding.declarations()) {
            if (item.function().equals(method) && item.functionClass().equals(className)) {
                return new Resolved(binding, item);
            }
        }
        return null;
    }

    /** Resolves a (qualified) name written in source to a type of a binding package, or null. */
    private TypeName typeName(SourceFile source, String dotted) {
        String[] parts = dotted.split("\\.");
        for (SourceFile.Import item : source.imports) {
            if (!item.isStatic() && !item.onDemand() && item.name().equals(parts[0])) {
                Binding binding = catalog.byJavaPackage(item.qualifier());
                if (binding != null) {
                    return new TypeName(binding, parts[0], rest(parts, 1));
                }
            }
        }
        // The members of a source's own package shadow the types it imports on demand, as in Java.
        Binding own = catalog.byJavaPackage(source.packageName);
        if (own != null && (own.types().containsKey(parts[0]) || declaresClass(own, parts[0]))) {
            return new TypeName(own, parts[0], rest(parts, 1));
        }
        for (SourceFile.Import item : source.imports) {
            if (!item.isStatic() && item.onDemand()) {
                Binding binding = catalog.byJavaPackage(item.qualifier());
                if (binding != null && (binding.types().containsKey(parts[0]) || declaresClass(binding, parts[0]))) {
                    return new TypeName(binding, parts[0], rest(parts, 1));
                }
            }
        }
        for (SourceFile.Import item : source.imports) {
            // import static a.b.Type.MEMBER: the simple name stands for a member of Type.
            if (item.isStatic() && !item.onDemand() && item.name().equals(parts[0])) {
                Binding binding = catalog.byJavaPackage(parent(item.qualifier()));
                String type = item.qualifier().substring(item.qualifier().lastIndexOf('.') + 1);
                if (binding != null && binding.types().containsKey(type)) {
                    List<String> tail = new ArrayList<>();
                    tail.add(parts[0]);
                    tail.addAll(rest(parts, 1));
                    return new TypeName(binding, type, tail);
                }
            }
        }
        for (int k = parts.length - 1; k >= 1; k--) {
            Binding binding = catalog.byJavaPackage(String.join(".", java.util.Arrays.copyOfRange(parts, 0, k)));
            if (binding != null) {
                return new TypeName(binding, parts[k], rest(parts, k + 1));
            }
        }
        return null;
    }

    private static boolean declaresClass(Binding binding, String name) {
        return binding.declarations().stream().anyMatch(item -> item.functionClass().equals(name));
    }

    private static List<String> rest(String[] parts, int from) {
        return new ArrayList<>(java.util.Arrays.asList(parts).subList(Math.min(from, parts.length), parts.length));
    }

    // ---- conditions --------------------------------------------------------------------------------------

    private ObjectNode condition(MethodInvocationTree call, Resolved target, SourceFile source, TreePath scope) {
        String where = source.where(call);
        Binding owner = target.binding();
        Binding.Declaration declaration = target.declaration();
        used.put(owner.root().toString(), owner);
        ObjectNode iface = null;
        Map<String, JsonNode> fields = new TreeMap<>();
        List<JsonNode> placed = new ArrayList<>();
        List<String> placedAt = new ArrayList<>();
        for (ExpressionTree argument : call.getArguments()) {
            String at = source.where(argument);
            Instance instance = argument(argument, source, scope, new HashSet<>());
            String nativeName = instance.entry().path("nativeName").asText();
            JsonNode root = null;
            for (JsonNode item : instance.binding().rootBindings()) {
                if (item.path("value").path("type").asText().equals(nativeName)
                        && item.path("scope").path("kind").asText().equals(declaration.sourceName())) {
                    root = item;
                    break;
                }
            }
            if (root == null) {
                throw new ProfilerException(STAGE, nativeName + " is not a field of the " + declaration.sourceName() + " declaration", at);
            }
            used.put(instance.binding().root().toString(), instance.binding());
            placed.add(root);
            placedAt.add(at);
            if (root.path("role").asText().equals("interface")) {
                if (iface != null) {
                    throw new ProfilerException(STAGE, "the " + declaration.sourceName() + " declaration has more than one interface", at);
                }
                iface = Json.object();
                iface.put("type", root.has("fixedInterfaceType") ? root.path("fixedInterfaceType").asText() : root.path("sourceName").asText());
                if (instance.value().isObject()) {
                    iface.setAll((ObjectNode) instance.value());
                }
            } else {
                String key = root.path("path").get(0).path("name").asText();
                if (fields.containsKey(key)) {
                    throw new ProfilerException(STAGE, "the field " + key + " is declared twice", at);
                }
                fields.put(key, instance.value());
            }
        }
        if (iface == null) {
            throw new ProfilerException(STAGE, "the " + declaration.sourceName() + " declaration has no interface", where);
        }
        for (int i = 0; i < placed.size(); i++) {
            String scoped = placed.get(i).path("scope").path("interfaceType").asText("");
            if (!scoped.isEmpty() && !scoped.equals(iface.path("type").asText())) {
                throw new ProfilerException(STAGE, placed.get(i).path("sourceName").asText() + " belongs to the " + scoped
                        + " interface, not " + iface.path("type").asText(), placedAt.get(i));
            }
        }
        ObjectNode condition = Json.object();
        condition.put("kind", declaration.sourceName());
        condition.set("interface", iface);
        fields.forEach(condition::set);
        return condition;
    }

    private Instance argument(ExpressionTree node, SourceFile source, TreePath scope, Set<Tree> seen) {
        ExpressionTree inner = unwrap(node);
        if (inner instanceof NewClassTree creation) {
            return instantiate(creation, source, scope);
        }
        Constant constant = constantOf(inner, source, scope);
        if (constant != null && seen.add(constant.node())) {
            return argument(constant.node(), constant.source(), constant.scope(), seen);
        }
        throw new ProfilerException(STAGE, "a declaration argument must be a new expression of a generated type", source.where(node));
    }

    private static ExpressionTree unwrap(ExpressionTree node) {
        ExpressionTree current = node;
        while (true) {
            if (current instanceof ParenthesizedTree paren) {
                current = paren.getExpression();
            } else if (current instanceof TypeCastTree cast) {
                current = cast.getExpression();
            } else {
                return current;
            }
        }
    }

    private Instance instantiate(NewClassTree creation, SourceFile source, TreePath scope) {
        String where = source.where(creation);
        if (creation.getClassBody() != null) {
            throw new ProfilerException(STAGE, "an anonymous subclass of a generated type cannot be read statically", where);
        }
        Tree identifier = creation.getIdentifier();
        if (identifier instanceof AnnotatedTypeTree annotated) {
            identifier = annotated.getUnderlyingType();
        }
        if (identifier instanceof ParameterizedTypeTree parameterized) {
            identifier = parameterized.getType();
        }
        String dotted = SourceFile.dotted(identifier);
        TypeName type = dotted == null ? null : typeName(source, dotted);
        if (type == null) {
            throw new ProfilerException(STAGE, "only types generated by a binding package on the classpath can be constructed", where);
        }
        Binding binding = type.binding();
        JsonNode entry = binding.types().get(type.name());
        if (entry == null) {
            throw new ProfilerException(STAGE, "only types generated by a binding package on the classpath can be constructed", where);
        }
        List<? extends ExpressionTree> arguments = creation.getArguments();
        String construct = entry.path("construct").asText();
        if (construct.equals("union")) {
            if (type.rest().size() != 1 || !type.rest().get(0).startsWith("Variant")) {
                throw new ProfilerException(STAGE, type.name() + " is a union; construct one of its variants, such as " + type.name() + ".Variant1", where);
            }
            int index;
            try {
                index = Integer.parseInt(type.rest().get(0).substring("Variant".length())) - 1;
            } catch (NumberFormatException e) {
                throw new ProfilerException(STAGE, type.name() + " has no variant " + type.rest().get(0), where);
            }
            JsonNode variants = entry.path("variants");
            if (index < 0 || index >= variants.size()) {
                throw new ProfilerException(STAGE, type.name() + " has no variant " + type.rest().get(0), where);
            }
            if (arguments.size() != 1) {
                throw new ProfilerException(STAGE, type.name() + "." + type.rest().get(0) + " takes one value", where);
            }
            JsonNode value = evaluate(arguments.get(0), source, scope, new Expected(binding, variants.get(index).path("value")), new HashSet<>());
            return new Instance(binding, entry, value);
        }
        if (!type.rest().isEmpty()) {
            throw new ProfilerException(STAGE, type.name() + " has no nested type " + type.rest().get(0), where);
        }
        switch (construct) {
            case "object" -> {
                JsonNode fields = entry.path("fields");
                if (arguments.size() != fields.size()) {
                    throw new ProfilerException(STAGE, type.name() + " takes " + fields.size() + " argument(s) but " + arguments.size() + " were given", where);
                }
                ObjectNode out = Json.object();
                for (int i = 0; i < fields.size(); i++) {
                    JsonNode field = fields.get(i);
                    ExpressionTree argument = arguments.get(i);
                    JsonNode value = evaluate(argument, source, scope, new Expected(binding, field.path("value")), new HashSet<>());
                    if (value.isNull()) {
                        if (field.path("required").asBoolean() && !isNullable(field.path("value"))) {
                            throw new ProfilerException(STAGE, type.name() + " is missing the required field " + field.path("nativeName").asText(), source.where(argument));
                        }
                        continue;
                    }
                    out.set(field.path("sourceName").asText(), value);
                }
                return new Instance(binding, entry, out);
            }
            case "collection", "map", "scalar", "wrapper", "any" -> {
                if (arguments.size() != 1) {
                    throw new ProfilerException(STAGE, type.name() + " takes one value", where);
                }
                if (construct.equals("scalar") && entry.has("members") && entry.path("members").size() > 0) {
                    throw new ProfilerException(STAGE, type.name() + " is an enum; use one of its constants", where);
                }
                // A wrapper carries one field, whose reference says what the value is; the others wrap an element or scalar.
                JsonNode reference = construct.equals("wrapper") ? entry.path("fields").get(0).path("value") : wrappedReference(entry);
                Expected inside = new Expected(binding, reference);
                return new Instance(binding, entry, evaluate(arguments.get(0), source, scope, inside, new HashSet<>()));
            }
            default -> throw new ProfilerException(STAGE, "unsupported generated construct " + construct, where);
        }
    }

    private static boolean isNullable(JsonNode ref) {
        String builtin = ref.path("builtin").asText("");
        return builtin.equals("Void") || builtin.equals("Object");
    }

    /** The reference describing the value a wrapper record carries: its element, or its underlying scalar. */
    private static JsonNode wrappedReference(JsonNode entry) {
        ObjectNode node = Json.object();
        switch (entry.path("construct").asText()) {
            case "collection", "map" -> node.put("type", entry.path("nativeName").asText()).put("container", true);
            case "scalar" -> node.put("builtin", javaBuiltin(entry.path("underlying").asText()));
            default -> node.put("builtin", "Object");
        }
        return node;
    }

    private static String javaBuiltin(String underlying) {
        return switch (underlying) {
            case "string", "str" -> "String";
            case "bool" -> "Boolean";
            case "int64", "int" -> "Long";
            case "float64", "float" -> "Double";
            default -> "Object";
        };
    }

    // ---- values ------------------------------------------------------------------------------------------

    private JsonNode evaluate(ExpressionTree node, SourceFile source, TreePath scope, Expected expected, Set<Tree> seen) {
        ExpressionTree inner = unwrap(node);
        String at = source.where(inner);
        if (inner instanceof LiteralTree literal && literal.getKind() == Tree.Kind.NULL_LITERAL) {
            return Json.NODES.nullNode();
        }
        JsonNode entry = expected == null || expected.ref().path("container").asBoolean(false) ? null : entryOf(expected);
        boolean container = expected != null && expected.ref().path("container").asBoolean(false);

        if (container) {
            return container(inner, source, scope, expected, at, seen);
        }
        if (inner instanceof NewClassTree creation) {
            Instance made = instantiate(creation, source, scope);
            if (expected != null && !container && expected.ref().has("type")) {
                String want = expected.ref().path("type").asText();
                String got = made.entry().path("nativeName").asText();
                if (!got.equals(want) && !isVariantOf(made, expected)) {
                    throw new ProfilerException(STAGE, "expected a " + want + " but found a " + got, at);
                }
            }
            used.put(made.binding().root().toString(), made.binding());
            return made.value();
        }
        if (entry != null && entry.path("construct").asText().equals("scalar") && entry.path("members").size() > 0) {
            return member(inner, source, scope, expected.binding(), entry, at);
        }
        if (entry != null && !entry.path("construct").asText().equals("any")) {
            Constant constant = constantOf(inner, source, scope);
            if (constant != null && seen.add(constant.node())) {
                return evaluate(constant.node(), constant.source(), constant.scope(), expected, seen);
            }
            throw new ProfilerException(STAGE, "a " + entry.path("nativeName").asText() + " must be constructed with new", at);
        }
        JsonNode value = literal(inner, source, scope, seen, at);
        if (expected != null && expected.ref().has("builtin")) {
            checkBuiltin(expected.ref().path("builtin").asText(), value, at);
        }
        return value;
    }

    private boolean isVariantOf(Instance made, Expected expected) {
        // A union variant record carries the union's own entry, so it is accepted where that union is expected.
        return made.entry().path("nativeName").asText().equals(expected.ref().path("type").asText());
    }

    private JsonNode entryOf(Expected expected) {
        return expected.ref().has("type") ? expected.binding().types().get(expected.ref().path("type").asText()) : null;
    }

    private JsonNode container(ExpressionTree inner, SourceFile source, TreePath scope, Expected expected, String at, Set<Tree> seen) {
        JsonNode owner = expected.binding().types().get(expected.ref().path("type").asText());
        JsonNode element = owner.path("element").path("value");
        if (owner.path("construct").asText().equals("collection")) {
            List<? extends ExpressionTree> items = listItems(inner, source, scope, at, seen);
            ArrayNode out = Json.array();
            for (ExpressionTree item : items) {
                out.add(evaluate(item, source, scope, new Expected(expected.binding(), element), new HashSet<>()));
            }
            return out;
        }
        List<ExpressionTree[]> pairs = mapPairs(inner, source, scope, at, seen);
        ObjectNode out = Json.object();
        for (ExpressionTree[] pair : pairs) {
            JsonNode key = literal(unwrap(pair[0]), source, scope, new HashSet<>(), source.where(pair[0]));
            if (!key.isTextual()) {
                throw new ProfilerException(STAGE, "a map key must be a string", source.where(pair[0]));
            }
            out.set(key.textValue(), evaluate(pair[1], source, scope, new Expected(expected.binding(), element), new HashSet<>()));
        }
        return out;
    }

    private List<? extends ExpressionTree> listItems(ExpressionTree node, SourceFile source, TreePath scope, String at, Set<Tree> seen) {
        if (node instanceof MethodInvocationTree call) {
            String name = SourceFile.dotted(call.getMethodSelect());
            if ("List.of".equals(name) || "Arrays.asList".equals(name) || "java.util.List.of".equals(name)
                    || "java.util.Arrays.asList".equals(name) || "Collections.singletonList".equals(name)
                    || "Collections.emptyList".equals(name) || "java.util.Collections.emptyList".equals(name)) {
                return call.getArguments();
            }
        }
        Constant constant = constantOf(node, source, scope);
        if (constant != null && seen.add(constant.node())) {
            return listItems(unwrap(constant.node()), constant.source(), constant.scope(), at, seen);
        }
        throw new ProfilerException(STAGE, "a list must be written with List.of, Arrays.asList or Collections.emptyList", at);
    }

    private List<ExpressionTree[]> mapPairs(ExpressionTree node, SourceFile source, TreePath scope, String at, Set<Tree> seen) {
        List<ExpressionTree[]> pairs = new ArrayList<>();
        if (node instanceof MethodInvocationTree call) {
            String name = SourceFile.dotted(call.getMethodSelect());
            if ("Map.of".equals(name) || "java.util.Map.of".equals(name)) {
                List<? extends ExpressionTree> arguments = call.getArguments();
                for (int i = 0; i + 1 < arguments.size(); i += 2) {
                    pairs.add(new ExpressionTree[] {arguments.get(i), arguments.get(i + 1)});
                }
                return pairs;
            }
            if ("Map.ofEntries".equals(name) || "java.util.Map.ofEntries".equals(name)) {
                for (ExpressionTree entry : call.getArguments()) {
                    ExpressionTree item = unwrap(entry);
                    String entryName = item instanceof MethodInvocationTree e ? SourceFile.dotted(e.getMethodSelect()) : null;
                    if (!"Map.entry".equals(entryName) && !"java.util.Map.entry".equals(entryName)) {
                        throw new ProfilerException(STAGE, "map entries must be written with Map.entry", source.where(entry));
                    }
                    List<? extends ExpressionTree> pair = ((MethodInvocationTree) item).getArguments();
                    pairs.add(new ExpressionTree[] {pair.get(0), pair.get(1)});
                }
                return pairs;
            }
            if ("Collections.emptyMap".equals(name) || "java.util.Collections.emptyMap".equals(name)) {
                return pairs;
            }
        }
        Constant constant = constantOf(node, source, scope);
        if (constant != null && seen.add(constant.node())) {
            return mapPairs(unwrap(constant.node()), constant.source(), constant.scope(), at, seen);
        }
        throw new ProfilerException(STAGE, "a map must be written with Map.of, Map.ofEntries or Collections.emptyMap", at);
    }

    private JsonNode member(ExpressionTree inner, SourceFile source, TreePath scope, Binding binding, JsonNode entry, String at) {
        String dotted = SourceFile.dotted(inner);
        if (dotted != null) {
            TypeName name = typeName(source, dotted);
            if (name != null && name.binding() == binding && name.name().equals(entry.path("nativeName").asText()) && name.rest().size() == 1) {
                for (JsonNode member : entry.path("members")) {
                    if (member.path("nativeName").asText().equals(name.rest().get(0))) {
                        return Json.NODES.textNode(member.path("value").asText());
                    }
                }
                throw new ProfilerException(STAGE, entry.path("nativeName").asText() + " has no constant " + name.rest().get(0), at);
            }
        }
        Constant constant = constantOf(inner, source, scope);
        if (constant != null) {
            return member(unwrap(constant.node()), constant.source(), constant.scope(), binding, entry, at);
        }
        if (dotted == null && !(inner instanceof LiteralTree)) {
            throw new ProfilerException(STAGE, "this value cannot be determined statically", at);
        }
        throw new ProfilerException(STAGE, "expected a constant of " + entry.path("nativeName").asText() + ", such as "
                + entry.path("nativeName").asText() + "." + entry.path("members").get(0).path("nativeName").asText(), at);
    }

    private static void checkBuiltin(String builtin, JsonNode value, String at) {
        boolean ok = switch (builtin) {
            case "String" -> value.isTextual();
            case "Boolean" -> value.isBoolean();
            case "Long" -> value.isIntegralNumber();
            case "Double" -> value.isNumber();
            case "Void" -> value.isNull();
            default -> true;
        };
        if (!ok) {
            throw new ProfilerException(STAGE, "expected a " + builtin + " value", at);
        }
    }

    /** A value that needs no generated type: a literal, constant, joined text, list, or map. */
    private JsonNode literal(ExpressionTree node, SourceFile source, TreePath scope, Set<Tree> seen, String at) {
        ExpressionTree inner = unwrap(node);
        if (inner instanceof LiteralTree literal) {
            Object value = literal.getValue();
            if (value == null) {
                return Json.NODES.nullNode();
            }
            if (value instanceof String text) {
                return Json.NODES.textNode(text);
            }
            if (value instanceof Boolean flag) {
                return Json.NODES.booleanNode(flag);
            }
            if (value instanceof Integer || value instanceof Long) {
                return Json.NODES.numberNode(((Number) value).longValue());
            }
            if (value instanceof Number number) {
                return Json.NODES.numberNode(number.doubleValue());
            }
            if (value instanceof Character c) {
                return Json.NODES.textNode(String.valueOf(c));
            }
        }
        if (inner instanceof UnaryTree unary && unary.getKind() == Tree.Kind.UNARY_MINUS) {
            JsonNode operand = literal(unary.getExpression(), source, scope, seen, at);
            if (operand.isIntegralNumber()) {
                return Json.NODES.numberNode(-operand.longValue());
            }
            if (operand.isNumber()) {
                return Json.NODES.numberNode(-operand.doubleValue());
            }
        }
        if (inner instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            JsonNode left = literal(binary.getLeftOperand(), source, scope, new HashSet<>(seen), at);
            JsonNode right = literal(binary.getRightOperand(), source, scope, new HashSet<>(seen), at);
            if (!left.isValueNode() || !right.isValueNode() || left.isNull() || right.isNull()) {
                throw new ProfilerException(STAGE, "only strings, numbers, and booleans can be joined statically", at);
            }
            if (left.isIntegralNumber() && right.isIntegralNumber()) {
                return Json.NODES.numberNode(left.longValue() + right.longValue());
            }
            return Json.NODES.textNode(left.asText() + right.asText());
        }
        if (inner instanceof MethodInvocationTree call) {
            String name = SourceFile.dotted(call.getMethodSelect());
            if (name != null && (name.equals("List.of") || name.equals("Arrays.asList") || name.equals("Collections.emptyList")
                    || name.equals("Collections.singletonList"))) {
                ArrayNode out = Json.array();
                for (ExpressionTree item : call.getArguments()) {
                    out.add(literal(item, source, scope, new HashSet<>(), source.where(item)));
                }
                return out;
            }
            if (name != null && (name.equals("Map.of") || name.equals("Map.ofEntries") || name.equals("Collections.emptyMap"))) {
                ObjectNode out = Json.object();
                for (ExpressionTree[] pair : mapPairs(inner, source, scope, at, new HashSet<>())) {
                    JsonNode key = literal(pair[0], source, scope, new HashSet<>(), source.where(pair[0]));
                    if (!key.isTextual()) {
                        throw new ProfilerException(STAGE, "a map key must be a string", source.where(pair[0]));
                    }
                    out.set(key.textValue(), literal(pair[1], source, scope, new HashSet<>(), source.where(pair[1])));
                }
                return out;
            }
        }
        Constant constant = constantOf(inner, source, scope);
        if (constant != null && seen.add(constant.node())) {
            return literal(constant.node(), constant.source(), constant.scope(), seen, at);
        }
        throw new ProfilerException(STAGE, "this value cannot be determined statically", at);
    }

    // ---- constants ---------------------------------------------------------------------------------------

    /** Finds the initializer of a final variable or field a simple or qualified name refers to. */
    private Constant constantOf(ExpressionTree node, SourceFile source, TreePath scope) {
        ExpressionTree inner = unwrap(node);
        if (inner instanceof IdentifierTree id) {
            return simpleConstant(id.getName().toString(), source, scope);
        }
        if (inner instanceof MemberSelectTree select) {
            String owner = SourceFile.dotted(select.getExpression());
            if (owner != null) {
                ClassTree type = projectClass(source, owner);
                if (type != null) {
                    return fieldOf(type, select.getIdentifier().toString());
                }
            }
        }
        return null;
    }

    private Constant simpleConstant(String name, SourceFile source, TreePath scope) {
        for (TreePath path = scope; path != null; path = path.getParentPath()) {
            Tree leaf = path.getLeaf();
            if (leaf instanceof BlockTree block) {
                for (StatementTree statement : block.getStatements()) {
                    if (statement instanceof VariableTree variable && variable.getName().contentEquals(name) && isFinal(variable)
                            && variable.getInitializer() != null) {
                        return new Constant(variable.getInitializer(), source, path);
                    }
                }
            } else if (leaf instanceof ClassTree type) {
                Constant found = fieldOf(type, name);
                if (found != null) {
                    return found;
                }
            }
        }
        for (SourceFile.Import item : source.imports) {
            if (!item.isStatic() || !(item.onDemand() || item.name().equals(name))) {
                continue;
            }
            ClassTree type = classes.get(item.qualifier());
            if (type != null) {
                Constant found = fieldOf(type, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Constant fieldOf(ClassTree type, String name) {
        for (Tree member : type.getMembers()) {
            if (member instanceof VariableTree variable && variable.getName().contentEquals(name)
                    && (isFinal(variable) || (type.getKind() == Tree.Kind.INTERFACE || type.getKind() == Tree.Kind.ANNOTATION_TYPE)) && variable.getInitializer() != null) {
                SourceFile file = fileOf(type);
                return new Constant(variable.getInitializer(), file, classPaths.get(type));
            }
        }
        return null;
    }

    private SourceFile fileOf(ClassTree type) {
        for (Map.Entry<String, ClassTree> entry : classes.entrySet()) {
            if (entry.getValue() == type) {
                return classFiles.get(entry.getKey());
            }
        }
        throw new IllegalStateException("class without a source file");
    }

    private static boolean isFinal(VariableTree variable) {
        return variable.getModifiers().getFlags().contains(Modifier.FINAL);
    }

    private ClassTree projectClass(SourceFile source, String dotted) {
        String[] parts = dotted.split("\\.");
        for (SourceFile.Import item : source.imports) {
            if (!item.isStatic() && !item.onDemand() && item.name().equals(parts[0])) {
                ClassTree found = classes.get(String.join(".", item.qualifier(), dotted));
                if (found != null) {
                    return found;
                }
            }
        }
        if (!source.packageName.isEmpty()) {
            ClassTree found = classes.get(source.packageName + "." + dotted);
            if (found != null) {
                return found;
            }
        } else if (classes.containsKey(dotted)) {
            return classes.get(dotted);
        }
        for (SourceFile.Import item : source.imports) {
            if (!item.isStatic() && item.onDemand()) {
                ClassTree found = classes.get(item.qualifier() + "." + dotted);
                if (found != null) {
                    return found;
                }
            }
        }
        return classes.get(dotted);
    }
}
