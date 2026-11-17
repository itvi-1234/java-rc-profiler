package w;

import example.conformance.heterogeneousunion.Bindings;
import example.conformance.heterogeneousunion.InterfaceTarget;
import example.conformance.heterogeneousunion.TargetTarget;
import example.conformance.heterogeneousunion.TargetValue;
import example.conformance.heterogeneousunion.TargetValueVariant1;

final class W {
    static final Object BY_OBJECT = Bindings.selector(
            new InterfaceTarget(), new TargetTarget(new TargetValue.Variant1(new TargetValueVariant1("a-1"))));
    static final Object BY_NAME = Bindings.selector(new InterfaceTarget(), new TargetTarget(new TargetValue.Variant2("name")));
}
