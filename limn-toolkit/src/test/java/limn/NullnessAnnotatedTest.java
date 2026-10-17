package limn;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssertTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import limn.testfixtures.RepositoryRoot;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public API says where null is allowed. Every package a module exports is
 * {@code @NullMarked}, so an unannotated type in it means "never null", and every public or
 * protected parameter or return value the API does let be null says so with {@code @Nullable}.
 *
 * <p>Read from the sources, through the compiler's own parser, because a compiled class cannot say
 * whether a type should have been nullable. A parameter or a return value counts as nullable when
 * the source says so in one of these ways, each a fact rather than a guess:
 * <ul>
 *   <li>its {@code @param} or {@code @return} text allows null: "or null", "null for none", "may be
 *       null". A mention that denies it ("never null", "not null", "non-null") does not count, and
 *       one about what a function answers or what an array holds ("may answer null", "each a tag or
 *       null") asks for a {@code @Nullable} inside the type instead: {@code Supplier<@Nullable Menu>};</li>
 *   <li>with no tag for it, the description allows null where it can only mean that part: the one
 *       reference parameter ("{@code null} clears it"), the parameter the clause names
 *       ("{@code icon == null} removes it"), or what a method answers ("the parent, or
 *       {@code null} while it is unattached");</li>
 *   <li>for a parameter, the body compares it with {@code null} and carries on rather than throwing
 *       at once, or hands it to a method of the same file that does;</li>
 *   <li>for a return value, the body returns the {@code null} literal, a conditional with a
 *       {@code null} branch, or the answer of a method of the same file that may be null, or of a
 *       nullable method of the API with the same name: an override that returns
 *       {@code super.hitTest(x)} or a container that returns {@code child.hitTest(x)} answers
 *       what those answer.</li>
 * </ul>
 *
 * <p>And what is nullable stays so along the way it travels: an overload that hands its nullable
 * parameter to another makes that one's nullable too, an override takes null wherever the method
 * it overrides does and answers null nowhere that method promises it will not, {@code equals}
 * takes a nullable {@code Object} as {@link Object#equals} does, and a public field this file sets
 * to null says so. A null that arrives through a private field is not traced; that is the
 * reviewer's to find.
 *
 * <p>The override rule reaches past the API: every override in the modules' sources, of an
 * exported package or an internal one, private or not, keeps the nullness of the method it
 * overrides, of the API or of an internal interface, and every {@code equals} takes a nullable
 * {@code Object}, since a checker run over the package holds each to it and a reader of the class
 * trusts what it says. The internal sources are read for their overrides and their interfaces'
 * declarations; the rest of this test reads the API alone.
 */
class NullnessAnnotatedTest {

    private static final List<String> MODULES =
            List.of("limn-toolkit", "limn-backend-lwjgl", "limn-video-ffmpeg", "limn-test");

    private static final Pattern EXPORTS = Pattern.compile("^\\s*exports\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final Pattern NULL = Pattern.compile("\\bnull\\b(?!-)", Pattern.CASE_INSENSITIVE);

    /** Where a clause ends, looking back from a mention of null. */
    private static final Pattern CLAUSE_END = Pattern.compile("[.;:,()\\u2014]|&mdash;");

    /** A clause that denies null: "never null", "must not be null", "non-null", "neither … nor null". */
    private static final Pattern DENIAL = Pattern.compile("\\b(?:never|not|no|nor|without)\\b|n't\\b|\\bnon-$",
            Pattern.CASE_INSENSITIVE);

    /** A clause whose null is what a function answers or what a container holds, not the value itself. */
    private static final Pattern INSIDE = Pattern.compile("\\b(?:answer(?:s|ed|ing)?|return(?:s|ed|ing)?|yields?|each)\\b",
            Pattern.CASE_INSENSITIVE);

    /** A clause about the elements of what is returned. */
    private static final Pattern EACH = Pattern.compile("\\beach\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Parameters whose documented null is what the function they take answers, in words the
     * patterns above cannot tell from a null for the parameter itself: these must carry the
     * {@code @Nullable} inside their type, as {@code Function<T, @Nullable String>}.
     */
    private static final Set<String> NULL_INSIDE = Set.of(
            "Column.text(value)",   // "the text of a row's cell; null draws an empty cell"
            "Column.of(value)");    // "a row's value; null draws an empty cell and sorts first"

    @Test
    void everyExportedPackageIsNullMarked() throws IOException {
        List<String> unmarked = new ArrayList<>();
        int packages = 0;
        for (Source source : exportedSources(RepositoryRoot.find())) {
            if (source.packageInfo()) {
                packages++;
                CompilationUnitTree unit = parse(List.of(source.path())).units().get(0);
                boolean marked = unit.getPackage() != null
                        && unit.getPackage().getAnnotations().stream().anyMatch(a -> named(a, "NullMarked"));
                if (!marked) {
                    unmarked.add(source.module() + ": " + source.pkg());
                }
            }
        }
        assertTrue(packages > 0, "no exported package was found");
        assertEquals(List.of(), unmarked, unmarked.size() + " exported package(s) are not @NullMarked");
    }

    @Test
    void everyNullablePartOfThePublicApiSaysSo() throws IOException {
        Scan scan = scan(RepositoryRoot.find());
        // Thousands of public methods, so a scan that read none cannot pass as one that found nothing.
        assertTrue(scan.apiMethods() > 1000, scan.apiMethods() + " public methods read");
        assertEquals(List.of(), scan.found(), scan.found().size() + " nullable part(s) of the public API lack @Nullable");
    }

    /** How many public methods and constructors were read, and what they lack, one line each. */
    record Scan(int apiMethods, List<String> found) {
    }

    /** What the second test reports; package-private so a planted defect can be run on its own. */
    static Scan scan(Path root) throws IOException {
        List<Source> sources = exportedSources(root).stream().filter(s -> !s.packageInfo()).toList();
        Parsed parsed = parse(sources.stream().map(Source::path).toList());
        List<Unit> units = new ArrayList<>();
        for (CompilationUnitTree unit : parsed.units()) {
            units.add(new Unit(unit, root.relativize(Path.of(unit.getSourceFile().toUri())).toString(), parsed));
        }
        // The API's nullable methods by name and arity, for a delegation to one of them with the
        // same name in another file. Recomputed with each round, as each round can add to it.
        Set<String> apiNullable = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Unit unit : units) {
                grew |= unit.settle(apiNullable);
            }
            for (Unit unit : units) {
                for (Method method : unit.methods) {
                    if (method.api && method.mayReturnNull) {
                        grew |= apiNullable.add(method.key());
                    }
                }
            }
        }
        List<String> found = new ArrayList<>();
        List<Source> hidden = internalSources(root);
        Parsed internal = parse(hidden.stream().map(Source::path).toList());
        List<Unit> declarations = new ArrayList<>();
        for (CompilationUnitTree unit : internal.units()) {
            declarations.add(new Unit(unit, root.relativize(Path.of(unit.getSourceFile().toUri())).toString(),
                    internal));
        }
        overrides(units, declarations, found);
        int apiMethods = 0;
        for (Unit unit : units) {
            unit.report(found);
            apiMethods += (int) unit.methods.stream().filter(m -> m.api).count();
        }
        return new Scan(apiMethods, found);
    }

    /**
     * An override may take more than the method it overrides and answer less, never the other way:
     * a parameter the overridden method takes as nullable stays nullable, a value it promises is
     * never null stays so, and {@code equals} takes a nullable {@code Object}, as
     * {@link Object#equals} does. The overridden method is looked up by its name and erased
     * parameter types in the toolkit's own supertypes of the overriding class, by their simple
     * names, so one from outside the toolkit is not seen, {@code equals} apart. Every override is
     * held to the method it overrides whatever its own visibility and wherever it is, and the
     * method overridden may be in an internal package ({@code declarations}): a package-private
     * window of the backend drops {@code NativeWindow}'s {@code @Nullable} no more than an API
     * class may, nor a private class an internal interface's.
     */
    private static void overrides(List<Unit> units, List<Unit> declarations, List<String> found) {
        record Declared(Unit unit, Method method) {
            boolean overrides() {
                return method.tree.getModifiers().getAnnotations().stream().anyMatch(a -> named(a, "Override"));
            }

            boolean takesNull(int i) {
                VariableTree parameter = method.tree.getParameters().get(i);
                return nullable(parameter.getModifiers(), parameter.getType(), method.nullableVariables);
            }

            boolean answersNull() {
                return method.returnsReference()
                        && nullable(method.tree.getModifiers(), method.tree.getReturnType(), method.nullableVariables);
            }

            String where() {
                return unit.file + ":" + unit.line(method.tree) + ": " + method.owner.getSimpleName() + "."
                        + method.name();
            }
        }
        java.util.Map<String, Set<String>> supertypes = new java.util.HashMap<>();
        List<Unit> everything = new ArrayList<>(units);
        everything.addAll(declarations);
        for (Unit unit : everything) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    Set<String> direct = supertypes.computeIfAbsent(node.getSimpleName().toString(), k -> new HashSet<>());
                    if (node.getExtendsClause() != null) {
                        direct.add(erasure(node.getExtendsClause()));
                    }
                    node.getImplementsClause().forEach(t -> direct.add(erasure(t)));
                    return super.visitClass(node, unused);
                }
            }.scan(unit.tree, null);
        }
        java.util.Map<String, List<Declared>> bySignature = new java.util.HashMap<>();
        for (Unit unit : everything) {
            for (Method method : unit.methods) {
                boolean internal = declarations.contains(unit);
                // The API's methods, any method that overrides, and an internal interface's
                // methods, which those may override.
                boolean counted = method.api
                        || method.tree.getModifiers().getAnnotations().stream()
                                .anyMatch(a -> named(a, "Override"))
                        || (internal && (method.owner.getKind() == Tree.Kind.INTERFACE));
                if (counted && method.tree.getReturnType() != null) {
                    String signature = method.name() + method.tree.getParameters().stream()
                            .map(p -> erasure(p.getType())).toList();
                    bySignature.computeIfAbsent(signature, k -> new ArrayList<>()).add(new Declared(unit, method));
                }
            }
        }
        for (List<Declared> family : bySignature.values()) {
            for (Declared override : family) {
                List<? extends VariableTree> parameters = override.method.tree.getParameters();
                if (override.method.name().equals("equals") && parameters.size() == 1
                        && erasure(parameters.get(0).getType()).equals("Object") && !override.takesNull(0)) {
                    found.add(override.where() + "(" + parameters.get(0).getName()
                            + "): equals takes null, as Object.equals does");
                }
                if (!override.overrides()) {
                    continue;
                }
                Set<String> ancestors = new HashSet<>();
                List<String> pending = new ArrayList<>(List.of(override.method.owner.getSimpleName().toString()));
                while (!pending.isEmpty()) {
                    for (String type : supertypes.getOrDefault(pending.remove(0), Set.of())) {
                        if (ancestors.add(type)) {
                            pending.add(type);
                        }
                    }
                }
                // Held to every supertype of the toolkit's it has, API or internal: a caller holding
                // the class as its interface, and a checker over its package, both read it so.
                List<Declared> overridden = family.stream()
                        .filter(d -> ancestors.contains(d.method.owner.getSimpleName().toString()))
                        .toList();
                for (int i = 0; i < parameters.size(); i++) {
                    int index = i;
                    if (!override.takesNull(i) && overridden.stream().anyMatch(d -> d.takesNull(index))) {
                        found.add(override.where() + "(" + parameters.get(i).getName()
                                + "): overrides a method that takes null here");
                    }
                }
                List<Declared> roots = overridden.stream().filter(d -> !d.overrides()).toList();
                if (override.answersNull() && !roots.isEmpty() && roots.stream().noneMatch(Declared::answersNull)) {
                    found.add(override.where() + " returns: answers null where the method it overrides "
                            + "promises not to");
                }
            }
        }
    }

    // ------------------------------------------------------------------------ one source file

    /** A method or constructor of a file, and what is known of its nulls so far. */
    private static final class Method {
        final ClassTree owner;
        final MethodTree tree;
        final boolean api;
        /** The type variables in scope declared {@code extends @Nullable Object}. */
        final Set<String> nullableVariables = new HashSet<>();
        final boolean[] acceptsNull;
        final String[] acceptsWhy;
        boolean mayReturnNull;
        String returnWhy;

        Method(ClassTree owner, MethodTree tree, boolean api) {
            this.owner = owner;
            this.tree = tree;
            this.api = api;
            this.acceptsNull = new boolean[tree.getParameters().size()];
            this.acceptsWhy = new String[acceptsNull.length];
            List<TypeParameterTree> variables = new ArrayList<>(owner.getTypeParameters());
            variables.addAll(tree.getTypeParameters());
            for (TypeParameterTree variable : variables) {
                if (variable.getBounds().stream().anyMatch(b -> b instanceof AnnotatedTypeTree a
                        && hasNullable(a.getAnnotations()))) {
                    nullableVariables.add(variable.getName().toString());
                }
            }
        }

        String name() {
            return tree.getReturnType() == null ? "<init>" : tree.getName().toString();
        }

        String key() {
            return name() + "/" + acceptsNull.length;
        }

        boolean returnsReference() {
            return tree.getReturnType() != null && !(tree.getReturnType() instanceof PrimitiveTypeTree);
        }
    }

    private static final class Unit {
        final CompilationUnitTree tree;
        final CharSequence source;
        final String file;
        final Parsed parsed;
        final List<Method> methods = new ArrayList<>();
        final List<ClassTree> records = new ArrayList<>();
        final List<VariableTree> fields = new ArrayList<>();

        Unit(CompilationUnitTree tree, String file, Parsed parsed) throws IOException {
            this.tree = tree;
            this.source = tree.getSourceFile().getCharContent(true);
            this.file = file;
            this.parsed = parsed;
            for (Tree type : tree.getTypeDecls()) {
                if (type instanceof ClassTree cls) {
                    collect(cls, accessible(cls.getModifiers(), false));
                }
            }
            for (Method method : methods) {
                List<? extends VariableTree> parameters = method.tree.getParameters();
                for (int i = 0; i < parameters.size(); i++) {
                    if (nullable(parameters.get(i).getModifiers(), parameters.get(i).getType(), method.nullableVariables)) {
                        method.acceptsNull[i] = true;
                        method.acceptsWhy[i] = "annotated";
                    }
                }
                if (method.returnsReference()
                        && nullable(method.tree.getModifiers(), method.tree.getReturnType(), method.nullableVariables)) {
                    method.mayReturnNull = true;
                    method.returnWhy = "annotated";
                }
            }
        }

        private void collect(ClassTree cls, boolean api) {
            boolean isInterface = cls.getKind() == Tree.Kind.INTERFACE || cls.getKind() == Tree.Kind.ANNOTATION_TYPE;
            if (api && cls.getKind() == Tree.Kind.RECORD) {
                records.add(cls);
            }
            for (Tree member : cls.getMembers()) {
                if (member instanceof ClassTree nested) {
                    collect(nested, api && accessible(nested.getModifiers(), isInterface));
                } else if (member instanceof MethodTree method) {
                    methods.add(new Method(cls, method, api && accessible(method.getModifiers(), isInterface)));
                } else if (member instanceof VariableTree field && api && !isInterface
                        && accessible(field.getModifiers(), false)) {
                    fields.add(field);
                }
            }
        }

        /** One round of what the bodies prove; whether anything was learned. */
        boolean settle(Set<String> apiNullable) {
            boolean learned = false;
            for (Method method : methods) {
                BlockTree body = method.tree.getBody();
                if (body == null) {
                    continue;
                }
                Set<String> shadowed = declaredInside(body);
                List<? extends VariableTree> parameters = method.tree.getParameters();
                // A compact constructor's parameters are the record's components, whose one type
                // serves the accessor too: a compact constructor that turns a null into a default
                // is lenient, and the component, which never answers null, stays unannotated.
                for (int i = 0; i < (compact(method) ? 0 : parameters.size()); i++) {
                    String name = parameters.get(i).getName().toString();
                    if (method.acceptsNull[i] || shadowed.contains(name)) {
                        continue;
                    }
                    String why = acceptsNull(method, body, name);
                    if (why != null) {
                        method.acceptsNull[i] = true;
                        method.acceptsWhy[i] = why;
                        learned = true;
                    }
                }
                if (method.returnsReference() && !method.mayReturnNull) {
                    String why = returnsNull(body, method, apiNullable);
                    if (why != null) {
                        method.mayReturnNull = true;
                        method.returnWhy = why;
                        learned = true;
                    }
                }
            }
            return learned;
        }

        void report(List<String> found) {
            for (Method method : methods) {
                if (!method.api) {
                    continue;
                }
                handsOn(method, found);
                String key = method.owner.getSimpleName() + "." + method.name();
                String where = file + ":" + line(method.tree) + ": " + key;
                DocCommentTree doc = parsed.docs().getDocCommentTree(TreePath.getPath(tree, method.tree));
                List<? extends VariableTree> parameters = method.tree.getParameters();
                for (int i = 0; i < parameters.size(); i++) {
                    VariableTree parameter = parameters.get(i);
                    String name = parameter.getName().toString();
                    check(where + "(" + name + ")", NULL_INSIDE.contains(key + "(" + name + ")"),
                            method.nullableVariables, parameter.getModifiers(), parameter.getType(),
                            paramText(doc, name), described(doc, method.tree, i, method.owner.getSimpleName().toString()),
                            method.acceptsNull[i] ? method.acceptsWhy[i] : null, found);
                }
                if (method.returnsReference()) {
                    check(where + " returns", false, method.nullableVariables, method.tree.getModifiers(),
                            method.tree.getReturnType(),
                            returnText(doc), described(doc, method.tree, -1, method.owner.getSimpleName().toString()),
                            method.mayReturnNull ? method.returnWhy : null, found);
                }
            }
            Set<String> setToNull = new HashSet<>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitAssignment(com.sun.source.tree.AssignmentTree node, Void unused) {
                    if (isNull(node.getExpression())) {
                        ExpressionTree target = node.getVariable();
                        setToNull.add(target instanceof MemberSelectTree member
                                ? member.getIdentifier().toString() : target.toString());
                    }
                    return super.visitAssignment(node, unused);
                }
            }.scan(tree, null);
            for (VariableTree field : fields) {
                boolean nulled = setToNull.contains(field.getName().toString())
                        || (field.getInitializer() != null && isNull(field.getInitializer()));
                if (nulled && !nullable(field.getModifiers(), field.getType())) {
                    found.add(file + ":" + line(field) + ": field " + field.getName() + ": this file sets it to null");
                }
            }
            for (ClassTree record : records) {
                DocCommentTree doc = parsed.docs().getDocCommentTree(TreePath.getPath(tree, record));
                for (Tree member : record.getMembers()) {
                    if (member instanceof VariableTree component
                            && !component.getModifiers().getFlags().contains(Modifier.STATIC)) {
                        String name = component.getName().toString();
                        check(file + ":" + line(component) + ": " + record.getSimpleName() + "(" + name + ")",
                                false, Set.of(), component.getModifiers(), component.getType(), paramText(doc, name), null, null,
                                found);
                    }
                }
            }
        }

        private void check(String where, boolean nullInside, Set<String> variables, ModifiersTree modifiers,
                           Tree type, String doc, String description, String bodyWhy, List<String> found) {
            Mentions mentions = doc == null ? Mentions.NONE : mentions(doc, !where.endsWith(" returns"));
            if (nullInside) {
                mentions = new Mentions(false, mentions.plain() || mentions.inside());
            }
            if (!nullable(modifiers, type, variables)) {
                if (mentions.plain()) {
                    found.add(where + ": documented as nullable: \"" + clip(doc) + "\"");
                } else if (description != null) {
                    found.add(where + ": described as nullable: \"..." + description + "...\"");
                } else if (bodyWhy != null && !bodyWhy.equals("annotated")) {
                    found.add(where + ": " + bodyWhy);
                }
            }
            if (mentions.inside() && !nullableInside(modifiers, type)) {
                found.add(where + ": documented as answering or holding null, which a @Nullable inside "
                        + "its type should say: \"" + clip(doc) + "\"");
            }
        }

        /**
         * An overload that hands its nullable parameter straight to another, as its first
         * statement, makes that other one take null too: {@code setIcon(icon)} that calls
         * {@code setIcon(icon, NEVER)} is only as nullable as the method it calls.
         */
        private void handsOn(Method method, List<String> found) {
            BlockTree body = method.tree.getBody();
            if (body == null || body.getStatements().isEmpty()) {
                return;
            }
            StatementTree first = body.getStatements().get(0);
            ExpressionTree expression = first instanceof com.sun.source.tree.ExpressionStatementTree statement
                    ? statement.getExpression()
                    : first instanceof com.sun.source.tree.ReturnTree r ? r.getExpression() : null;
            if (!(expression instanceof MethodInvocationTree call) || !sameFile(call)) {
                return;
            }
            List<? extends VariableTree> parameters = method.tree.getParameters();
            List<? extends ExpressionTree> arguments = call.getArguments();
            for (int i = 0; i < parameters.size(); i++) {
                if (!nullable(parameters.get(i).getModifiers(), parameters.get(i).getType(), method.nullableVariables)) {
                    continue;
                }
                for (int j = 0; j < arguments.size(); j++) {
                    if (!isName(arguments.get(j), parameters.get(i).getName().toString())) {
                        continue;
                    }
                    for (Method target : overloads(calleeName(call), arguments, method)) {
                        VariableTree taken = target.tree.getParameters().get(j);
                        if (target.api && target != method
                                && !nullable(taken.getModifiers(), taken.getType(), target.nullableVariables)) {
                            found.add(file + ":" + line(target.tree) + ": " + target.owner.getSimpleName() + "."
                                    + target.name() + "(" + taken.getName() + "): takes the null that "
                                    + method.owner.getSimpleName() + "." + method.name() + " hands it");
                        }
                    }
                }
            }
        }

        /** Why the body accepts null for this parameter, or null if it does not. */
        private String acceptsNull(Method caller, BlockTree body, String name) {
            if (rejectsNull(body, name)) {
                return null;
            }
            String[] why = {null};
            new TreeScanner<Void, Boolean>() {
                @Override
                public Void visitIf(IfTree node, Boolean rejecting) {
                    scan(node.getCondition(), throwsAtOnce(node.getThenStatement()));
                    scan(node.getThenStatement(), false);
                    scan(node.getElseStatement(), false);
                    return null;
                }

                @Override
                public Void visitAssert(AssertTree node, Boolean rejecting) {
                    return null;
                }

                @Override
                public Void visitBinary(BinaryTree node, Boolean rejecting) {
                    if (why[0] == null && (node.getKind() == Tree.Kind.EQUAL_TO || node.getKind() == Tree.Kind.NOT_EQUAL_TO)
                            && comparesWithNull(node, name) && !Boolean.TRUE.equals(rejecting)) {
                        why[0] = "the body compares it with null and carries on";
                    }
                    return super.visitBinary(node, rejecting);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Boolean rejecting) {
                    String callee = calleeName(node);
                    List<? extends ExpressionTree> arguments = node.getArguments();
                    if (why[0] == null && (callee.equals("requireNonNullElse") || callee.equals("requireNonNullElseGet"))
                            && !arguments.isEmpty() && isName(arguments.get(0), name)) {
                        why[0] = "the body gives it a default with " + callee;
                    }
                    if (why[0] == null && sameFile(node)) {
                        for (int i = 0; i < arguments.size() && why[0] == null; i++) {
                            if (isName(arguments.get(i), name)) {
                                for (Method target : overloads(callee, arguments, caller)) {
                                    if (target.acceptsNull[i]) {
                                        why[0] = "the body hands it to " + target.owner.getSimpleName() + "."
                                                + target.name() + ", which accepts null";
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    return super.visitMethodInvocation(node, rejecting);
                }

                @Override
                public Void visitClass(ClassTree node, Boolean rejecting) {
                    return null; // a local or anonymous class is another scope
                }
            }.scan(body, false);
            return why[0];
        }

        /**
         * Whether the body refuses null for this parameter outright: {@code requireNonNull} on it,
         * or a comparison with null whose branch throws at once. A later check is then about
         * something else, and not an acceptance.
         */
        private static boolean rejectsNull(BlockTree body, String name) {
            boolean[] rejects = {false};
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitIf(IfTree node, Void unused) {
                    if (throwsAtOnce(node.getThenStatement()) && mentionsNullCheck(node.getCondition(), name)) {
                        rejects[0] = true;
                    }
                    return super.visitIf(node, unused);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    if (calleeName(node).equals("requireNonNull") && !node.getArguments().isEmpty()
                            && isName(node.getArguments().get(0), name)) {
                        rejects[0] = true;
                    }
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    return null;
                }

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                    return null;
                }
            }.scan(body, null);
            return rejects[0];
        }

        private static boolean mentionsNullCheck(ExpressionTree condition, String name) {
            boolean[] found = {false};
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitBinary(BinaryTree node, Void unused) {
                    found[0] |= node.getKind() == Tree.Kind.EQUAL_TO && comparesWithNull(node, name);
                    return super.visitBinary(node, unused);
                }
            }.scan(condition, null);
            return found[0];
        }

        /** Why the body may return null, or null if nothing says it does. */
        private String returnsNull(BlockTree body, Method method, Set<String> apiNullable) {
            String[] why = {null};
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitReturn(com.sun.source.tree.ReturnTree node, Void unused) {
                    if (why[0] == null && node.getExpression() != null) {
                        why[0] = mayBeNull(node.getExpression(), method, apiNullable);
                    }
                    return null;
                }

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                    return null; // its returns are the lambda's
                }

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    return null;
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    return scan(node.getArguments(), null); // not an anonymous body
                }
            }.scan(body, null);
            return why[0];
        }

        private String mayBeNull(ExpressionTree expression, Method method, Set<String> apiNullable) {
            expression = strip(expression);
            if (isNull(expression)) {
                return "returns null";
            }
            if (expression instanceof ConditionalExpressionTree conditional) {
                String yes = mayBeNull(conditional.getTrueExpression(), method, apiNullable);
                return yes != null ? yes : mayBeNull(conditional.getFalseExpression(), method, apiNullable);
            }
            if (expression instanceof MethodInvocationTree call) {
                String callee = calleeName(call);
                int arity = call.getArguments().size();
                if (sameFile(call)) {
                    for (Method target : overloads(callee, call.getArguments(), method)) {
                        if (target.mayReturnNull) {
                            return "returns what " + target.owner.getSimpleName() + "." + target.name()
                                    + " returns, which may be null";
                        }
                    }
                }
                boolean delegates = callee.equals(method.name()) && arity == method.acceptsNull.length
                        && method.tree.getModifiers().getAnnotations().stream().anyMatch(a -> named(a, "Override"));
                boolean toSuper = call.getMethodSelect() instanceof MemberSelectTree select
                        && isName(select.getExpression(), "super");
                if ((delegates || toSuper) && apiNullable.contains(callee + "/" + arity)) {
                    return "returns what a nullable " + callee + " of the API returns";
                }
            }
            return null;
        }

        /** A call this file can answer for: unqualified, or on {@code this} or {@code super}. */
        private static boolean sameFile(MethodInvocationTree call) {
            ExpressionTree select = call.getMethodSelect();
            return select instanceof IdentifierTree
                    || (select instanceof MemberSelectTree member
                    && (isName(member.getExpression(), "this") || isName(member.getExpression(), "super")));
        }

        /**
         * The methods of this file a call may reach: the name and the arity, and where an argument
         * is one of the caller's parameters, a parameter of the same erased type there, so that
         * {@code bytes(Class, …)} is not taken for {@code bytes(InputStream, …)}.
         */
        private List<Method> overloads(String name, List<? extends ExpressionTree> arguments, Method caller) {
            List<Method> found = new ArrayList<>();
            boolean constructor = name.equals("this");
            for (Method method : methods) {
                if (method.acceptsNull.length != arguments.size()
                        || !(constructor ? method.name().equals("<init>") : method.name().equals(name))) {
                    continue;
                }
                boolean fits = true;
                for (int i = 0; i < arguments.size() && fits; i++) {
                    for (VariableTree parameter : caller.tree.getParameters()) {
                        if (isName(arguments.get(i), parameter.getName().toString())) {
                            String wanted = erasure(method.tree.getParameters().get(i).getType());
                            fits = wanted.equals(erasure(parameter.getType())) || wanted.equals("Object")
                                    || wanted.matches("[A-Z]");
                        }
                    }
                }
                if (fits) {
                    found.add(method);
                }
            }
            return found;
        }

        /** A record's compact constructor, whose declaration has no parameter list of its own. */
        private boolean compact(Method method) {
            if (method.owner.getKind() != Tree.Kind.RECORD || method.tree.getReturnType() != null
                    || method.tree.getBody() == null) {
                return false;
            }
            var positions = parsed.trees().getSourcePositions();
            long start = positions.getStartPosition(tree, method.tree);
            long body = positions.getStartPosition(tree, method.tree.getBody());
            return !source.subSequence((int) start, (int) body).toString().contains("(");
        }

        private long line(Tree node) {
            return tree.getLineMap().getLineNumber(parsed.trees().getSourcePositions().getStartPosition(tree, node));
        }
    }

    // ------------------------------------------------------------------------ what the source says

    /**
     * Whether a declaration's type is annotated {@code @Nullable} at its outermost level: among the
     * declaration's annotations for a simple or parameterized type, and on the type itself for an
     * array ({@code String @Nullable []}) or a qualified name ({@code Map.@Nullable Entry}). A
     * {@code @Nullable String[]} is an array of nullable strings, not a nullable array.
     */
    private static boolean nullable(ModifiersTree modifiers, Tree type, Set<String> variables) {
        if (type instanceof IdentifierTree id && variables.contains(id.getName().toString())
                && modifiers.getAnnotations().isEmpty()) {
            return true; // a T declared extends @Nullable Object carries its nullness with it
        }
        return nullable(modifiers, type);
    }

    private static boolean nullable(ModifiersTree modifiers, Tree type) {
        if (type instanceof AnnotatedTypeTree annotated) {
            return hasNullable(annotated.getAnnotations());
        }
        if (type instanceof ParameterizedTypeTree parameterized && parameterized.getType() instanceof AnnotatedTypeTree) {
            return nullable(modifiers, parameterized.getType());
        }
        return !(type instanceof ArrayTypeTree) && hasNullable(modifiers.getAnnotations());
    }

    /** Whether a {@code @Nullable} sits inside the type: on a type argument, a bound or an array's component. */
    private static boolean nullableInside(ModifiersTree modifiers, Tree type) {
        if (type instanceof ArrayTypeTree && hasNullable(modifiers.getAnnotations())) {
            return true;
        }
        boolean[] inside = {false};
        new TreeScanner<Void, Boolean>() {
            @Override
            public Void visitAnnotatedType(AnnotatedTypeTree node, Boolean outermost) {
                inside[0] |= !outermost && hasNullable(node.getAnnotations());
                return scan(node.getUnderlyingType(), outermost);
            }

            @Override
            public Void visitParameterizedType(ParameterizedTypeTree node, Boolean outermost) {
                scan(node.getType(), outermost);
                return scan(node.getTypeArguments(), false);
            }

            @Override
            public Void visitArrayType(ArrayTypeTree node, Boolean outermost) {
                return scan(node.getType(), false);
            }

            @Override
            public Void visitWildcard(WildcardTree node, Boolean outermost) {
                return scan(node.getBound(), false);
            }
        }.scan(type, true);
        return inside[0];
    }

    /** A type's simple name without its arguments or annotations: {@code Map.Entry<K, V>} is {@code Entry}. */
    private static String erasure(Tree type) {
        if (type instanceof AnnotatedTypeTree annotated) {
            return erasure(annotated.getUnderlyingType());
        }
        if (type instanceof ParameterizedTypeTree parameterized) {
            return erasure(parameterized.getType());
        }
        if (type instanceof ArrayTypeTree array) {
            return erasure(array.getType()) + "[]";
        }
        if (type instanceof MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        return type.toString();
    }

    private static boolean hasNullable(List<? extends AnnotationTree> annotations) {
        return annotations.stream().anyMatch(a -> named(a, "Nullable"));
    }

    private static boolean named(AnnotationTree annotation, String simpleName) {
        String name = annotation.getAnnotationType().toString();
        return name.equals(simpleName) || name.endsWith("." + simpleName);
    }

    /** Which kinds of mention of null a text makes: of the value itself, and of what is inside it. */
    record Mentions(boolean plain, boolean inside) {
        static final Mentions NONE = new Mentions(false, false);
    }

    /**
     * @param parameter whether the text is a parameter's, where "answers null" is what a function
     *                  answers; in a return value's it is what the method itself answers
     */
    static Mentions mentions(String text, boolean parameter) {
        boolean plain = false;
        boolean inside = false;
        Matcher m = NULL.matcher(text);
        while (m.find()) {
            String before = text.substring(0, m.start());
            Matcher end = CLAUSE_END.matcher(before);
            int start = 0;
            while (end.find()) {
                start = end.end();
            }
            String clause = before.substring(start);
            if (DENIAL.matcher(clause).find()) {
                continue;
            }
            if (parameter ? INSIDE.matcher(clause).find() : EACH.matcher(clause).find()) {
                inside = true;
            } else {
                plain = true;
            }
        }
        return new Mentions(plain, inside);
    }

    /**
     * The words of a method's description that allow null for one of its parts, when no tag
     * speaks for that part: a mention names the only reference parameter, or the parameter it
     * sits beside, or what the method returns: for a method with no reference parameter, any
     * mention, and for one with some, a mention in the first sentence that names none of them, of
     * a method that does not answer with its own type as a fluent setter does. A mention of what
     * something answers or holds is left to the tags, which are precise about it.
     *
     * @param index the parameter's index, or -1 for the return value
     * @return the words around the mention, or null when there is none
     */
    private static String described(DocCommentTree doc, MethodTree method, int index, String owner) {
        if (doc == null) {
            return null;
        }
        List<? extends VariableTree> parameters = method.getParameters();
        List<VariableTree> references = parameters.stream()
                .filter(p -> !(p.getType() instanceof PrimitiveTypeTree)).map(p -> (VariableTree) p).toList();
        String name;
        if (index < 0) {
            if (returnText(doc) != null) {
                return null;
            }
            if (!references.isEmpty()) {
                String returned = erasure(method.getReturnType());
                return returned.equals(owner) || returned.equals("W") ? null
                        : describedReturn(text(doc.getFirstSentence()), references);
            }
            name = null;
        } else {
            name = parameters.get(index).getName().toString();
            if (paramText(doc, name) != null || !references.contains(parameters.get(index))) {
                return null;
            }
        }
        String body = text(doc.getFullBody());
        Matcher m = NULL.matcher(body);
        while (m.find()) {
            String clause = clauseBefore(body, m.start());
            if (DENIAL.matcher(clause).find() || INSIDE.matcher(clause).find()) {
                continue;
            }
            String around = body.substring(Math.max(0, m.start() - 40), Math.min(body.length(), m.end() + 40));
            if (name == null || references.size() == 1) {
                return around;
            }
            // Among several, the one the clause names: "{@code icon == null}", "a {@code null} parent".
            Pattern named = Pattern.compile("(?<![-\\w])" + Pattern.quote(name) + "(?![-\\w])");
            String after = body.substring(m.end(), Math.min(body.length(), m.end() + 12));
            if (named.matcher(clause).find() || named.matcher(after).find()) {
                return around;
            }
        }
        return null;
    }

    /** A first sentence's mention of null that names no parameter: "…, or {@code null} when …". */
    private static String describedReturn(String sentence, List<VariableTree> references) {
        Matcher m = NULL.matcher(sentence);
        while (m.find()) {
            String clause = clauseBefore(sentence, m.start());
            String after = sentence.substring(m.end(), Math.min(sentence.length(), m.end() + 12));
            boolean namesOne = references.stream().map(r -> Pattern.compile(
                            "(?<![-\\w])" + Pattern.quote(r.getName().toString()) + "(?![-\\w])"))
                    .anyMatch(named -> named.matcher(clause).find() || named.matcher(after).find());
            if (!namesOne && !DENIAL.matcher(clause).find() && !INSIDE.matcher(clause).find()
                    && !after.matches("\\W*to\\b.*")) {
                return sentence.substring(Math.max(0, m.start() - 40), Math.min(sentence.length(), m.end() + 40));
            }
        }
        return null;
    }

    private static String clauseBefore(String text, int position) {
        String before = text.substring(0, position);
        Matcher end = CLAUSE_END.matcher(before);
        int start = 0;
        while (end.find()) {
            start = end.end();
        }
        return before.substring(start);
    }

    private static boolean throwsAtOnce(StatementTree statement) {
        if (statement instanceof ThrowTree) {
            return true;
        }
        return statement instanceof BlockTree block && !block.getStatements().isEmpty()
                && block.getStatements().get(block.getStatements().size() - 1) instanceof ThrowTree;
    }

    private static boolean comparesWithNull(BinaryTree node, String name) {
        return (isName(node.getLeftOperand(), name) && isNull(node.getRightOperand()))
                || (isNull(node.getLeftOperand()) && isName(node.getRightOperand(), name));
    }

    private static String calleeName(MethodInvocationTree call) {
        ExpressionTree select = call.getMethodSelect();
        if (select instanceof IdentifierTree id) {
            return id.getName().toString();
        }
        return select instanceof MemberSelectTree member ? member.getIdentifier().toString() : "";
    }

    private static ExpressionTree strip(ExpressionTree tree) {
        while (true) {
            if (tree instanceof ParenthesizedTree p) {
                tree = p.getExpression();
            } else if (tree instanceof TypeCastTree cast) {
                tree = cast.getExpression();
            } else {
                return tree;
            }
        }
    }

    private static boolean isName(ExpressionTree tree, String name) {
        return strip(tree) instanceof IdentifierTree id && id.getName().contentEquals(name);
    }

    private static boolean isNull(ExpressionTree tree) {
        return strip(tree) instanceof LiteralTree literal && literal.getKind() == Tree.Kind.NULL_LITERAL;
    }

    /** Names a body declares itself, a lambda's parameters among them, which hide a parameter's. */
    private static Set<String> declaredInside(BlockTree body) {
        Set<String> names = new HashSet<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                names.add(node.getName().toString());
                return super.visitVariable(node, unused);
            }
        }.scan(body, null);
        return names;
    }

    /** Public, or protected (a subclass's API), or a member of an interface, which is public unless private. */
    private static boolean accessible(ModifiersTree modifiers, boolean inInterface) {
        Set<Modifier> flags = modifiers.getFlags();
        return flags.contains(Modifier.PUBLIC) || flags.contains(Modifier.PROTECTED)
                || (inInterface && !flags.contains(Modifier.PRIVATE));
    }

    private static String paramText(DocCommentTree doc, String name) {
        if (doc == null) {
            return null;
        }
        for (DocTree tag : doc.getBlockTags()) {
            if (tag instanceof ParamTree param && !param.isTypeParameter()
                    && param.getName().getName().contentEquals(name)) {
                return text(param.getDescription());
            }
        }
        return null;
    }

    private static String returnText(DocCommentTree doc) {
        if (doc == null) {
            return null;
        }
        for (DocTree tag : doc.getBlockTags()) {
            if (tag instanceof ReturnTree r) {
                return text(r.getDescription());
            }
        }
        for (DocTree part : doc.getFirstSentence()) {
            if (part instanceof ReturnTree r) {
                return text(r.getDescription());
            }
        }
        return null;
    }

    private static String text(List<? extends DocTree> parts) {
        StringBuilder sb = new StringBuilder();
        for (DocTree part : parts) {
            sb.append(part);
        }
        return sb.toString().replaceAll("\\s+", " ").strip();
    }

    private static String clip(String text) {
        return text.length() <= 90 ? text : text.substring(0, 87) + "...";
    }

    // ------------------------------------------------------------------------ reading the modules

    record Source(String module, String pkg, Path path, boolean packageInfo) {
    }

    private record Parsed(List<CompilationUnitTree> units, DocTrees docs, com.sun.source.util.Trees trees) {
    }

    /** The sources of every package a module does not export, read for their declarations. */
    static List<Source> internalSources(Path root) throws IOException {
        List<Source> sources = new ArrayList<>();
        Set<Path> exported = new HashSet<>();
        for (Source source : exportedSources(root)) {
            exported.add(source.path());
        }
        for (String module : MODULES) {
            Path main = root.resolve(module).resolve("src/main/java");
            try (Stream<Path> files = Files.walk(main)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!exported.contains(file) && !name.equals("package-info.java")
                            && !name.equals("module-info.java")) {
                        sources.add(new Source(module, "", file, false));
                    }
                }
            }
        }
        return sources;
    }

    static List<Source> exportedSources(Path root) throws IOException {
        List<Source> sources = new ArrayList<>();
        for (String module : MODULES) {
            Path main = root.resolve(module).resolve("src/main/java");
            Matcher exports = EXPORTS.matcher(Files.readString(main.resolve("module-info.java")));
            while (exports.find()) {
                String pkg = exports.group(1);
                try (Stream<Path> files = Files.list(main.resolve(pkg.replace('.', '/')))) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                        sources.add(new Source(module, pkg, file,
                                file.getFileName().toString().equals("package-info.java")));
                    }
                }
            }
        }
        return sources;
    }

    private static Parsed parse(List<Path> files) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        List<String> errors = new ArrayList<>();
        JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostic -> {
            if (diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                errors.add(diagnostic.toString());
            }
        }, List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(files));
        List<CompilationUnitTree> units = new ArrayList<>();
        task.parse().forEach(units::add);
        // A file the parser cannot read is a file nothing here checked.
        assertEquals(List.of(), errors, "the sources did not parse");
        return new Parsed(units, DocTrees.instance(task), com.sun.source.util.Trees.instance(task));
    }
}
