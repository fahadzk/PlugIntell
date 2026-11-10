package com.core.docintel.pipeline.web;
import com.core.docintel.pipeline.events.ApplicationServices;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.*;
import java.util.Map;
@Controller("/api/v1/application")
@Requires(beans=ApplicationServices.class)
public final class ApplicationController{
 private final ApplicationServices services;
 public ApplicationController(ApplicationServices services){this.services=services;}
 @Get public Map<String,Object> application(){return services.application();}
}

