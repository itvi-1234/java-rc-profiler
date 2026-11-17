package w;

import example.conformance.ownedkindinterface.Bindings;
import example.conformance.ownedkindinterface.Http;
import example.conformance.ownedkindinterface.Region;

final class W {
    static String endpoint() {
        return System.getenv("ENDPOINT");
    }

    static final Object SERVICE = Bindings.service(new Http(endpoint()), new Region("eu"));
}
