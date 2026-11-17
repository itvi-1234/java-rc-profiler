package w;

import example.conformance.ownedkindinterface.Bindings;
import example.conformance.ownedkindinterface.Http;
import example.conformance.ownedkindinterface.Region;

final class W {
    static final Object SERVICE = Bindings.service(new Http(null), new Region("eu"));
}
