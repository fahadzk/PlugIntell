package com.core.docintel.pipeline.builder;

import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import java.net.URI;
import java.util.NoSuchElementException;

@Controller
public final class PipelineBuilderController {
    private final PluginCatalog catalog;
    private final PipelineService service;
    private final PipelineProjectGenerator generator;
    private final GraphPipelineValidator graphValidator;
    private final PipelineControlService control;
    private final GraphExecutionManager executions;
    private final ChunkingPreviewService chunkingPreview;
    private final IngestionUploadService uploads;
    private final PluginConnectionTestService connectionTests;
    private final ConnectionProfileService connectionProfiles;
    private final ProjectBuildManager projectBuilds;
    public PipelineBuilderController(PluginCatalog catalog,PipelineService service,PipelineProjectGenerator generator,GraphPipelineValidator graphValidator,PipelineControlService control,GraphExecutionManager executions,ChunkingPreviewService chunkingPreview,IngestionUploadService uploads,PluginConnectionTestService connectionTests,ConnectionProfileService connectionProfiles,ProjectBuildManager projectBuilds){this.catalog=catalog;this.service=service;this.generator=generator;this.graphValidator=graphValidator;this.control=control;this.executions=executions;this.chunkingPreview=chunkingPreview;this.uploads=uploads;this.connectionTests=connectionTests;this.connectionProfiles=connectionProfiles;this.projectBuilds=projectBuilds;}
    @Get("/") public HttpResponse<?> home(){return HttpResponse.redirect(URI.create("/ui/index.html"));}
    @Get("/api/plugins") public java.util.List<PluginInfo> plugins(){return catalog.all();}
    @Get(uri="/api/v1/plugins", produces=MediaType.APPLICATION_JSON)
    public java.util.List<PluginDescriptor> pluginDescriptors(){return catalog.descriptors();}
    @Post(uri="/api/v1/plugins/reload", consumes=MediaType.ALL, produces=MediaType.APPLICATION_JSON)
    public java.util.List<PluginDescriptor> reloadPlugins(){catalog.reload();return catalog.descriptors();}
    @Get(uri="/api/v1/plugins/{id}", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> pluginDescriptor(String id){return catalog.descriptor(id).<HttpResponse<?>>map(HttpResponse::ok).orElseGet(HttpResponse::notFound);}
    @Get(uri="/api/v1/plugins/{id}/schema", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> pluginSchema(String id){return catalog.descriptor(id).<HttpResponse<?>>map(p->HttpResponse.ok(p.configurationSchema())).orElseGet(HttpResponse::notFound);}
    @Post(uri="/api/v1/plugins/{id}/validate-config", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> validatePluginConfig(String id,@Body java.util.Map<String,Object> config){
        if(catalog.all().stream().noneMatch(plugin->plugin.id().equals(id)))return HttpResponse.notFound(java.util.Map.of("error","Unknown plugin: "+id));
        PluginInfo plugin=catalog.all().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();
        return HttpResponse.ok(graphValidator.validatePluginConfiguration(id,plugin.version(),config,catalog.all(),true));
    }
    @Post(uri="/api/v1/plugins/{id}/test-connection", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> testPluginConnection(String id,@Body PluginConnectionTestRequest request){try{return HttpResponse.ok(connectionTests.test(id,request==null?null:request.config()));}catch(NoSuchElementException e){return HttpResponse.notFound(java.util.Map.of("error",e.getMessage()));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Get(uri="/api/v1/connections", produces=MediaType.APPLICATION_JSON)
    public java.util.List<ConnectionProfileRecord> connectionProfiles(){return connectionProfiles.list();}
    @Post(uri="/api/v1/connections", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> createConnectionProfile(@Body ConnectionProfileRequest request){try{return HttpResponse.created(connectionProfiles.create(request));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Get(uri="/api/v1/connections/{id}", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> connectionProfile(String id){return connectionProfiles.get(id).<HttpResponse<?>>map(HttpResponse::ok).orElseGet(HttpResponse::notFound);}
    @Patch(uri="/api/v1/connections/{id}", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> updateConnectionProfile(String id,@Body ConnectionProfileRequest request){try{return HttpResponse.ok(connectionProfiles.update(id,request));}catch(ConnectionProfileInUseException|ConnectionProfileConflictException e){return HttpResponse.status(HttpStatus.CONFLICT).body(java.util.Map.of("error",e.getMessage()));}catch(NoSuchElementException e){return HttpResponse.notFound(java.util.Map.of("error",e.getMessage()));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Delete(uri="/api/v1/connections/{id}")
    public HttpResponse<?> deleteConnectionProfile(String id,@QueryValue long revision){try{connectionProfiles.delete(id,revision);return HttpResponse.noContent();}catch(ConnectionProfileInUseException|ConnectionProfileConflictException e){return HttpResponse.status(HttpStatus.CONFLICT).body(java.util.Map.of("error",e.getMessage()));}catch(NoSuchElementException e){return HttpResponse.notFound(java.util.Map.of("error",e.getMessage()));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Post(uri="/api/v1/connections/{id}/test", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> testConnectionProfile(String id){try{return HttpResponse.ok(connectionProfiles.test(id));}catch(NoSuchElementException e){return HttpResponse.notFound(java.util.Map.of("error",e.getMessage()));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Post(uri="/api/pipelines/validate", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public PipelineValidation validate(@Body PipelineRequest request){return service.validate(request);}
    @Post(uri="/api/v1/pipelines/validate", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> validateGraph(@Body PipelineDefinition definition){try{return HttpResponse.ok(control.validate(definition,true));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Post(uri="/api/v1/ingestion/preview", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> previewChunking(@Body ChunkingPreviewRequest request){try{return HttpResponse.ok(chunkingPreview.preview(request));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Post(uri="/api/v1/ingestion/uploads", consumes=MediaType.MULTIPART_FORM_DATA, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> uploadIngestionFile(@Part("file") io.micronaut.http.multipart.CompletedFileUpload file){try{return HttpResponse.created(uploads.save(file));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}catch(IllegalStateException e){return HttpResponse.serverError(java.util.Map.of("error",e.getMessage()));}}
    @Get(uri="/api/v1/pipelines", produces=MediaType.APPLICATION_JSON)
    public java.util.List<PipelineRecord> pipelines(){return control.list();}
    @Post(uri="/api/v1/pipelines/import", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> importPipeline(@Body PipelineDefinition definition){
        try{return HttpResponse.created(control.create(definition));}
        catch(PipelineControlService.InvalidPipelineException e){return HttpResponse.unprocessableEntity().body(e.validation());}
        catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}
    }
    @Post(uri="/api/v1/pipelines", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> createPipeline(@Body PipelineDefinition definition){
        try{return HttpResponse.created(control.create(definition));}
        catch(PipelineControlService.InvalidPipelineException e){return HttpResponse.unprocessableEntity().body(e.validation());}
        catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}
    }
    @Get(uri="/api/v1/pipelines/{id}", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> getPipeline(String id){return control.get(id).<HttpResponse<?>>map(HttpResponse::ok).orElseGet(HttpResponse::notFound);}
    @Get(uri="/api/v1/pipelines/{id}/export", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> exportPipeline(String id){return control.get(id).<HttpResponse<?>>map(record->HttpResponse.ok(record.definition())).orElseGet(HttpResponse::notFound);}
    @Put(uri="/api/v1/pipelines/{id}", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> updatePipeline(String id,@Body UpdatePipelineRequest request){
        try{return HttpResponse.ok(control.update(id,request.expectedRevision(),request.definition()));}
        catch(PipelineConflictException e){return HttpResponse.status(HttpStatus.CONFLICT).body(java.util.Map.of("error",e.getMessage()));}
        catch(PipelineControlService.InvalidPipelineException e){return HttpResponse.unprocessableEntity().body(e.validation());}
        catch(IllegalArgumentException e){return HttpResponse.notFound(java.util.Map.of("error",e.getMessage()));}
    }
    @Delete(uri="/api/v1/pipelines/{id}")
    public HttpResponse<?> deletePipeline(String id,@QueryValue long revision){
        try{control.delete(id,revision);return HttpResponse.noContent();}
        catch(PipelineConflictException e){return HttpResponse.status(HttpStatus.CONFLICT).body(java.util.Map.of("error",e.getMessage()));}
    }
    @Post(uri="/api/v1/pipelines/{id}/publish", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> publishPipeline(String id,@Body PublishPipelineRequest request){
        try{return HttpResponse.ok(control.publish(id,request.expectedRevision()));}
        catch(PipelineConflictException e){return HttpResponse.status(HttpStatus.CONFLICT).body(java.util.Map.of("error",e.getMessage()));}
        catch(PipelineControlService.InvalidPipelineException e){return HttpResponse.unprocessableEntity().body(e.validation());}
        catch(IllegalArgumentException e){return HttpResponse.notFound(java.util.Map.of("error",e.getMessage()));}
    }
    @Get(uri="/api/v1/pipelines/{id}/versions", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> pipelineVersions(String id){try{return HttpResponse.ok(control.versions(id));}catch(IllegalArgumentException e){return HttpResponse.notFound(java.util.Map.of("error",e.getMessage()));}}
    @Get(uri="/api/v1/pipelines/{id}/versions/{version}", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> pipelineVersion(String id,int version){return control.version(id,version).<HttpResponse<?>>map(HttpResponse::ok).orElseGet(HttpResponse::notFound);}
    @Post(uri="/api/v1/executions", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> startExecution(@Body ExecutionRequest request){try{return HttpResponse.status(HttpStatus.ACCEPTED).body(executions.start(request));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Post(uri="/api/v1/query/execute", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> executeQuery(@Body ExecutionRequest request){try{return HttpResponse.status(HttpStatus.ACCEPTED).body(executions.start(request,PipelineType.QUERY_PROCESSING));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Post(uri="/api/v1/ingestion/jobs", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> startIngestion(@Body ExecutionRequest request){try{return HttpResponse.status(HttpStatus.ACCEPTED).body(executions.start(request,PipelineType.DOCUMENT_INGESTION));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Get(uri="/api/v1/ingestion/jobs", produces=MediaType.APPLICATION_JSON)
    public java.util.List<ExecutionSnapshot> ingestionJobs(){return executionsByType(PipelineType.DOCUMENT_INGESTION);}
    @Get(uri="/api/v1/ingestion/jobs/{id}", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> ingestionJob(String id){return executionOfType(id,PipelineType.DOCUMENT_INGESTION);}
    @Post(uri="/api/v1/ingestion/jobs/{id}/cancel")
    public HttpResponse<?> cancelIngestionJob(String id){return cancelExecution(id);}
    @Get(uri="/api/v1/query/executions", produces=MediaType.APPLICATION_JSON)
    public java.util.List<ExecutionSnapshot> queryExecutions(){return executionsByType(PipelineType.QUERY_PROCESSING);}
    @Get(uri="/api/v1/query/executions/{id}", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> queryExecution(String id){return executionOfType(id,PipelineType.QUERY_PROCESSING);}
    @Get(uri="/api/v1/executions/{id}", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> execution(String id){return executions.get(id).<HttpResponse<?>>map(HttpResponse::ok).orElseGet(HttpResponse::notFound);}
    @Get(uri="/api/v1/executions", produces=MediaType.APPLICATION_JSON)
    public java.util.List<ExecutionSnapshot> executions(){return executions.list();}
    @Get(uri="/api/v1/executions/{id}/events", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> executionEvents(String id,@QueryValue(defaultValue="0") long afterSequence){try{return HttpResponse.ok(executions.events(id,afterSequence));}catch(IllegalArgumentException e){return e.getMessage()!=null&&e.getMessage().contains("afterSequence")?HttpResponse.badRequest(java.util.Map.of("error",e.getMessage())):HttpResponse.notFound(java.util.Map.of("error",e.getMessage()));}}
    @Get(uri="/api/v1/executions/{id}/trace", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> executionTrace(String id,@QueryValue(defaultValue="0") long afterSequence){return executionEvents(id,afterSequence);}
    @Post(uri="/api/v1/executions/{id}/cancel")
    public HttpResponse<?> cancelExecution(String id){return executions.cancel(id)?HttpResponse.accepted():HttpResponse.notFound(java.util.Map.of("error","Execution is not active or does not exist."));}
    @Post(uri="/api/v1/projects/builds", consumes=MediaType.APPLICATION_JSON, produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> startProjectBuild(@Body ProjectBuildRequest request){try{return HttpResponse.status(HttpStatus.ACCEPTED).body(projectBuilds.start(request));}catch(IllegalArgumentException e){return HttpResponse.badRequest(java.util.Map.of("error",e.getMessage()));}}
    @Get(uri="/api/v1/projects/builds/{id}", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<?> projectBuild(String id){return projectBuilds.get(id).<HttpResponse<?>>map(HttpResponse::ok).orElseGet(HttpResponse::notFound);}
    @Get(uri="/api/v1/projects/builds/{id}/artifact")
    public HttpResponse<?> projectBuildArtifact(String id){
        var artifact=projectBuilds.artifact(id);if(artifact.isEmpty())return HttpResponse.notFound(java.util.Map.of("error","Build artifact is not ready or the job does not exist."));
        try{String filename=artifact.get().getFileName().toString().replaceAll("[^A-Za-z0-9_.-]","_");return HttpResponse.ok(java.nio.file.Files.readAllBytes(artifact.get())).contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition","attachment; filename=\""+filename+"\"");}
        catch(java.io.IOException e){return HttpResponse.serverError(java.util.Map.of("error","Could not read the build artifact."));}
    }
    @Get(uri="/api/v1/projects/builds/{id}/bundle")
    public HttpResponse<?> projectBuildBundle(String id){
        var snapshot=projectBuilds.get(id);var bundle=projectBuilds.bundle(id);
        if(snapshot.isEmpty()||bundle.isEmpty())return HttpResponse.notFound(java.util.Map.of("error","Project bundle is not ready or the job does not exist."));
        return HttpResponse.ok(bundle.get()).contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition","attachment; filename=\""+snapshot.get().projectName()+".zip\"");
    }
    private java.util.List<ExecutionSnapshot> executionsByType(PipelineType type){return executions.list().stream().filter(snapshot->control.version(snapshot.pipelineId(),snapshot.pipelineVersion()).map(version->version.definition().type()==type).orElse(false)).toList();}
    private HttpResponse<?> executionOfType(String id,PipelineType type){return executions.get(id).filter(snapshot->control.version(snapshot.pipelineId(),snapshot.pipelineVersion()).map(version->version.definition().type()==type).orElse(false)).<HttpResponse<?>>map(HttpResponse::ok).orElseGet(HttpResponse::notFound);}
    @Post(uri="/api/pipelines/generate", consumes=MediaType.APPLICATION_JSON)
    public HttpResponse<?> generate(@Body PipelineRequest request){
        try {
            byte[] content=generator.generate(request);
            String filename=(request.projectName()==null?"pipeline":request.projectName())+".zip";
            return HttpResponse.ok(content).contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition","attachment; filename=\""+filename.replaceAll("[^A-Za-z0-9_.-]","_")+"\"");
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(java.util.Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return HttpResponse.serverError(java.util.Map.of("error", e.getMessage()));
        }
    }
}
