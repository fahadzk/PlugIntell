package com.core.docintel.pipeline.builder;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.*;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import java.util.Map;

@Filter("/api/v1/**")
public final class ControlPlaneAuthFilter implements HttpServerFilter {
    private final ControlPlaneAuthService auth;
    public ControlPlaneAuthFilter(ControlPlaneAuthService auth){this.auth=auth;}

    @Override public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request,ServerFilterChain chain){
        if(request.getPath().equals("/api/v1/auth/login"))return chain.proceed(request);
        if(!auth.enabled()){
            if(mutating(request.getMethodName()))auth.audit(null,request.getMethodName(),request.getPath(),request.getHeaders().get("X-Request-ID"),"AUTHORIZED_LOCAL");
            return chain.proceed(request);
        }
        String authorization=request.getHeaders().get("Authorization");
        String token=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):null;
        var user=auth.authenticate(token);
        if(user.isEmpty()){
            if(mutating(request.getMethodName()))auth.audit(null,request.getMethodName(),request.getPath(),request.getHeaders().get("X-Request-ID"),"DENIED_UNAUTHENTICATED");
            return Publishers.just(HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","Authentication required or session expired.")));
        }
        if(!auth.allowed(user.get(),request.getPath(),request.getMethodName())){
            auth.audit(user.get(),request.getMethodName(),request.getPath(),request.getHeaders().get("X-Request-ID"),"DENIED_FORBIDDEN");
            return Publishers.just(HttpResponse.status(HttpStatus.FORBIDDEN).body(Map.of("error","The current role is not allowed to perform this operation.")));
        }
        if(mutating(request.getMethodName()))auth.audit(user.get(),request.getMethodName(),request.getPath(),request.getHeaders().get("X-Request-ID"),"AUTHORIZED");
        return chain.proceed(request);
    }
    private static boolean mutating(String method){return !java.util.Set.of("GET","HEAD","OPTIONS").contains(method.toUpperCase(java.util.Locale.ROOT));}
}
