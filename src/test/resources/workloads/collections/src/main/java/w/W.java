package w;

import example.conformance.collectionsandmaps.*;
import java.util.List;
import java.util.Map;

final class W {
    static final Object SHAPES = Bindings.collection(
            new Items(),
            new Tags(new TagsValue(List.of("a", "b"))),
            new Labels(new LabelsValue(Map.of("k", "v"))),
            new Entries(new EntriesValue(List.of(new EntriesItem("n", 1L)))));
}
