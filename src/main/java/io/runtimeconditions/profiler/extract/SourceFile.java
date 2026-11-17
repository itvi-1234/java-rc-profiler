package io.runtimeconditions.profiler.extract;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One parsed Java source file with its imports. Nothing in it is compiled or resolved by the compiler. */
final class SourceFile {
    /** An import such as a.b.C, a.b.*, or static a.b.C.member. */
    record Import(String qualifier, String name, boolean isStatic, boolean onDemand) {
        String full() {
            return onDemand ? qualifier + ".*" : qualifier + "." + name;
        }
    }

    final Path path;
    final String relative;
    final CompilationUnitTree unit;
    final String packageName;
    final List<Import> imports = new ArrayList<>();
    private final SourcePositions positions;
    private final LineMap lines;

    SourceFile(Path path, String relative, CompilationUnitTree unit, SourcePositions positions) {
        this.path = path;
        this.relative = relative;
        this.unit = unit;
        this.positions = positions;
        this.lines = unit.getLineMap();
        this.packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
        for (ImportTree item : unit.getImports()) {
            String text = item.getQualifiedIdentifier().toString();
            int dot = text.lastIndexOf('.');
            if (dot < 0) {
                continue;
            }
            String qualifier = text.substring(0, dot);
            String name = text.substring(dot + 1);
            imports.add(new Import(qualifier, name, item.isStatic(), name.equals("*")));
        }
    }

    String where(Tree node) {
        long start = positions.getStartPosition(unit, node);
        if (start < 0) {
            return relative;
        }
        return relative + ":" + lines.getLineNumber(start) + ":" + lines.getColumnNumber(start);
    }

    List<ClassTree> topLevelClasses() {
        List<ClassTree> classes = new ArrayList<>();
        for (Tree type : unit.getTypeDecls()) {
            if (type instanceof ClassTree tree) {
                classes.add(tree);
            }
        }
        return classes;
    }

    /** The dotted text of a name expression, or null when the tree is not a plain (qualified) name. */
    static String dotted(Tree tree) {
        if (tree instanceof com.sun.source.tree.IdentifierTree id) {
            return id.getName().toString();
        }
        if (tree instanceof MemberSelectTree select) {
            String left = dotted(select.getExpression());
            return left == null ? null : left + "." + select.getIdentifier();
        }
        return null;
    }
}
