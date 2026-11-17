package w;

import example.conformance.recursivereference.Bindings;
import example.conformance.recursivereference.InterfaceNode;
import example.conformance.recursivereference.NodeChildren;
import example.conformance.recursivereference.NodeNode;
import example.conformance.recursivereference.Root;
import example.conformance.recursivereference.RootChildren;
import java.util.List;

final class W {
    static final Object TREE = Bindings.tree(
            new InterfaceNode(),
            new Root(
                    "root",
                    new RootChildren(List.of(
                            new NodeNode("a", null),
                            new NodeNode("b", new NodeChildren(List.of(new NodeNode("c", null))))))));
}
