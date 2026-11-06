package com.core.docintel.pipeline.builder;

import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import java.util.Map;

@Controller("/api/v1/auth")
public final class AuthController {
    private final ControlPlaneAuthService auth;
    public AuthController(ControlPlaneAuthService auth){this.auth=auth;}

    @Post(uri="/login",consumes=MediaType.APPLICATION_JSON,produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> login(@Body LoginRequest request){if(!auth.enabled())return HttpResponse.notFound();try{return HttpResponse.ok(auth.login(request.username(),request.password()));}catch(SecurityException e){return HttpResponse.unauthorized().body(Map.of("error","Invalid username or password."));}}

    @Post(uri="/logout",produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> logout(HttpRequest<?> request){if(!auth.enabled())return HttpResponse.notFound();String token=bearer(request);auth.logout(token);return HttpResponse.ok(Map.of("loggedOut",true));}

    @Get(uri="/me",produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> me(HttpRequest<?> request){if(!auth.enabled())return HttpResponse.notFound();return auth.authenticate(bearer(request)).<HttpResponse<?>>map(HttpResponse::ok).orElseGet(HttpResponse::unauthorized);}

    @Get(uri="/users",produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> users(){return auth.enabled()?HttpResponse.ok(auth.users()):HttpResponse.notFound();}

    @Post(uri="/users",consumes=MediaType.APPLICATION_JSON,produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> createUser(@Body CreateUserRequest request){if(!auth.enabled())return HttpResponse.notFound();try{return HttpResponse.created(auth.createUser(request.username(),request.password(),request.role()));}catch(IllegalArgumentException e){return HttpResponse.badRequest(Map.of("error",e.getMessage()));}}

    @Patch(uri="/users/{id}",consumes=MediaType.APPLICATION_JSON,produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> updateUser(String id,@Body UpdateUserRequest request){if(!auth.enabled())return HttpResponse.notFound();try{return HttpResponse.ok(auth.updateUser(id,request.role(),request.enabled()));}catch(IllegalArgumentException e){return HttpResponse.badRequest(Map.of("error",e.getMessage()));}}

    private static String bearer(HttpRequest<?> request){String value=request.getHeaders().get("Authorization");return value!=null&&value.startsWith("Bearer ")?value.substring(7):null;}
}
