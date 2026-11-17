package w;

import example.conformance.ownedkindinterface.Bindings;
import example.conformance.ownedkindinterface.Http;
import example.conformance.ownedkindinterface.Region;

final class W {
    static {
        if (true) {
            throw new IllegalStateException("the workload must never be executed");
        }
    }

    static final Object SERVICE = Bindings.service(new Http("https://example.com"), new Region("eu"));
}
