package com.core.docintel.pipeline.builder;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import java.util.List;

@Controller("/api/v1/audit-events")
public final class AuditController {
    private final ControlPlaneAuthService auth;
    public AuditController(ControlPlaneAuthService auth){this.auth=auth;}
    @Get public HttpResponse<?> recent(@QueryValue(defaultValue="100")int limit){return auth.enabled()?HttpResponse.ok(auth.auditEvents(limit)):HttpResponse.notFound();}
}
