package forms;

import static forms.Paths.HOST;

import example.conformance.ownedkindinterface.*;

final class Owned {
    static final String ENDPOINT = HOST + "/v" + 1;

    private final String instance = Paths.HOST + "/instance";

    void declare() {
        final String local = ENDPOINT + "/local";
        example.conformance.ownedkindinterface.Bindings.service(new Http(ENDPOINT), new Region(Paths.REGION));
        Bindings.service(new Http((String) local), new Region(("west")));
        Bindings.service(new Http(instance), new Region("south"));
    }
}
