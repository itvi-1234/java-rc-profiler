package w;

import example.conformance.additivefield.Credential;
import example.conformance.additivefielddependency.Bindings;
import example.conformance.additivefielddependency.Http;

final class W {
    static final Object SERVICE = Bindings.service(new Http(), new Credential("fixture-token"));
}
