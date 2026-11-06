package com.core.docintel.pipeline.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ControlPlaneAuthServiceTest {
    @TempDir Path temp;

    @Test void bootstrapsHashedAdminSessionsAndRolePermissions(){
        var auth=new ControlPlaneAuthService(temp.resolve("auth.db").toString(),true,"admin","bootstrap-password-123");auth.initialize();
        var token=auth.login("admin","bootstrap-password-123");
        assertEquals("ADMIN",token.role());assertTrue(auth.authenticate(token.accessToken()).isPresent());
        var viewer=auth.createUser("read.user","viewer-password-123","VIEWER");
        var operator=auth.createUser("ops.user","operator-password-123","OPERATOR");
        assertTrue(auth.allowed(viewer,"/api/v1/pipelines","GET"));
        assertFalse(auth.allowed(viewer,"/api/v1/pipelines","POST"));
        assertTrue(auth.allowed(operator,"/api/v1/ingestion/jobs","POST"));
        assertFalse(auth.allowed(operator,"/api/v1/pipelines","POST"));
        assertThrows(IllegalArgumentException.class,()->auth.updateUser(auth.users().getFirst().id(),null,false));
        var viewerToken=auth.login("read.user","viewer-password-123");
        var operatorToken=auth.login("ops.user","operator-password-123");
        var promoted=auth.updateUser(operator.id(),"EDITOR",null);assertEquals("EDITOR",promoted.role());assertTrue(promoted.enabled());
        var disabled=auth.updateUser(viewer.id(),null,false);assertFalse(disabled.enabled());
        assertTrue(auth.authenticate(viewerToken.accessToken()).isEmpty());
        auth.logout(token.accessToken());assertTrue(auth.authenticate(token.accessToken()).isEmpty());
        assertTrue(auth.authenticate(operatorToken.accessToken()).isPresent());
    }

    @Test void requiresLongPasswordsAndKnownRoles(){
        var auth=new ControlPlaneAuthService(temp.resolve("auth-validation.db").toString(),false,"admin","");auth.initialize();
        assertThrows(IllegalArgumentException.class,()->auth.createUser("test.user","short","VIEWER"));
        assertThrows(IllegalArgumentException.class,()->auth.createUser("test.user","long-enough-password","ROOT"));
    }

    @Test void auditRecordsRouteAndActorWithoutRequestPayload(){
        var auth=new ControlPlaneAuthService(temp.resolve("audit.db").toString(),true,"admin","bootstrap-password-123");auth.initialize();
        var user=auth.authenticate(auth.login("admin","bootstrap-password-123").accessToken()).orElseThrow();
        auth.audit(user,"POST","/api/v1/pipelines","request-123","AUTHORIZED");
        var event=auth.auditEvents(1).getFirst();
        assertEquals("admin",event.actor());assertEquals("POST",event.method());assertEquals("request-123",event.requestId());
        assertFalse(event.path().contains("password"));
    }
}
