package forms;

import example.conformance.collectionsandmaps.Bindings;
import example.conformance.collectionsandmaps.Items;
import example.conformance.collectionsandmaps.Labels;
import example.conformance.collectionsandmaps.LabelsValue;
import example.conformance.collectionsandmaps.Tags;
import example.conformance.collectionsandmaps.TagsValue;
import example.conformance.heterogeneousunion.InterfaceTarget;
import example.conformance.heterogeneousunion.TargetTarget;
import example.conformance.heterogeneousunion.TargetValue;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

final class Others {
    private static final String TAG = "static";

    void declare() {
        Bindings.collection(
                new Items(),
                new Tags(new TagsValue(Arrays.asList(TAG, "dynamic"))),
                new Labels(new LabelsValue(Map.ofEntries(Map.entry("a", "1"), Map.entry("b", TAG)))));
        Bindings.collection(new Items(), new Tags(new TagsValue(Collections.emptyList())), new Labels(new LabelsValue(Collections.emptyMap())));
        example.conformance.heterogeneousunion.Bindings.selector(new InterfaceTarget(), new TargetTarget(new TargetValue.Variant2(TAG + "-name")));
    }
}
