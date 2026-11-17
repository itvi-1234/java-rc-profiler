package w;

import example.conformance.ownedkindinterface.Bindings;
import example.conformance.transitiveclosure.Command;

final class W {
    static final Object SERVICE = Bindings.service(new Command("run"));
}
