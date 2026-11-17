package w;

import example.conformance.scopeddomainscollisions.*;

final class W {
    static final Object HTTP = Bindings.service(new Http(), new HttpMode(HttpModeValue.PROXY), new BaseUrl("https://x"));
    static final Object GRPC = Bindings.service(new Grpc(), new GrpcMode(GrpcModeValue.STREAMING));
}
