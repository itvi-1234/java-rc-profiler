package w;

import example.conformance.ownedkindinterface.Bindings;
import example.conformance.ownedkindinterface.Http;

final class W {
    // The extension's schema requires a region.
    static final Object SERVICE = Bindings.service(new Http("https://example.com"));
}
