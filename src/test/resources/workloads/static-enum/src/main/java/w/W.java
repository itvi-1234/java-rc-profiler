package w;

import static example.conformance.scopeddomainscollisions.GrpcModeValue.STREAMING;
import static example.conformance.scopeddomainscollisions.HttpModeValue.PROXY;

import example.conformance.scopeddomainscollisions.Bindings;
import example.conformance.scopeddomainscollisions.Grpc;
import example.conformance.scopeddomainscollisions.GrpcMode;
import example.conformance.scopeddomainscollisions.Http;
import example.conformance.scopeddomainscollisions.HttpMode;

final class W {
    // The same constant name could belong to another enum; the static import says which one this is.
    static final Object HTTP = Bindings.service(new Http(), new HttpMode(PROXY));
    static final Object GRPC = Bindings.service(new Grpc(), new GrpcMode(STREAMING));
}
