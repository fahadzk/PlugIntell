package com.core.docintel.pipeline.builder;

import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** SQLite-backed users and short-lived bearer sessions for the optional control-plane RBAC layer. */
@Singleton
public final class ControlPlaneAuthService {
    private static final int ITERATIONS=210_000;
    private static final int KEY_BITS=256;
    private static final Duration SESSION_LIFETIME=Duration.ofHours(8);
    private final Path databasePath;
    private final boolean enabled;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final SecureRandom random=new SecureRandom();

    public ControlPlaneAuthService(@Value("${pipeline.store.sqlite-path:./data/plugintell.db}")String databasePath,
                                   @Value("${control.auth.enabled:false}")boolean enabled,
                                   @Value("${control.auth.bootstrap-admin-username:admin}")String bootstrapUsername,
                                   @Value("${control.auth.bootstrap-admin-password:}")String bootstrapPassword){
        this.databasePath=Path.of(databasePath).toAbsolutePath().normalize();this.enabled=enabled;
        this.bootstrapUsername=bootstrapUsername;this.bootstrapPassword=bootstrapPassword;
    }

    @PostConstruct public synchronized void initialize(){
        try{
            Files.createDirectories(databasePath.getParent());Class.forName("org.sqlite.JDBC");
            try(Connection connection=connection();Statement statement=connection.createStatement()){
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS studio_users(id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, salt BLOB NOT NULL, password_hash BLOB NOT NULL, role TEXT NOT NULL, enabled INTEGER NOT NULL, created_at TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS auth_sessions(token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, expires_at TEXT NOT NULL, FOREIGN KEY(user_id) REFERENCES studio_users(id) ON DELETE CASCADE)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS audit_events(id INTEGER PRIMARY KEY AUTOINCREMENT,actor TEXT NOT NULL,role TEXT NOT NULL,method TEXT NOT NULL,path TEXT NOT NULL,outcome TEXT NOT NULL,request_id TEXT NOT NULL,created_at TEXT NOT NULL)");
            }
            if(bootstrapPassword!=null&&!bootstrapPassword.isBlank()&&!userExists(bootstrapUsername))createUser(bootstrapUsername,bootstrapPassword,"ADMIN");
            if(enabled&&userCount()==0)throw new IllegalStateException("Control-plane authentication is enabled, but no users exist. Set CONTROL_PLANE_BOOTSTRAP_ADMIN_PASSWORD for the first startup.");
        }catch(Exception e){if(e instanceof IllegalStateException state)throw state;throw new IllegalStateException("Could not initialize control-plane authentication: "+e.getMessage(),e);}
    }

    public boolean enabled(){return enabled;}

    public synchronized AuthToken login(String username,String password){
        if(username==null||password==null)throw new SecurityException("Invalid username or password.");
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT id,salt,password_hash,role,enabled FROM studio_users WHERE username=?")){
            p.setString(1,username);try(ResultSet r=p.executeQuery()){
                if(!r.next()||r.getInt("enabled")!=1||!MessageDigest.isEqual(r.getBytes("password_hash"),hash(password.toCharArray(),r.getBytes("salt"))))throw new SecurityException("Invalid username or password.");
                String token=randomToken(),expires=Instant.now().plus(SESSION_LIFETIME).toString();
                try(PreparedStatement session=c.prepareStatement("INSERT INTO auth_sessions(token_hash,user_id,expires_at) VALUES(?,?,?)")){session.setString(1,sha256(token));session.setString(2,r.getString("id"));session.setString(3,expires);session.executeUpdate();}
                return new AuthToken(token,"Bearer",username,r.getString("role"),expires);
            }
        }catch(SQLException e){throw failure(e);}
    }

    public synchronized Optional<UserSummary> authenticate(String token){
        if(token==null||token.isBlank())return Optional.empty();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT u.id,u.username,u.role,u.enabled,u.created_at,s.expires_at FROM auth_sessions s JOIN studio_users u ON u.id=s.user_id WHERE s.token_hash=?")){
            p.setString(1,sha256(token));try(ResultSet r=p.executeQuery()){
                if(!r.next())return Optional.empty();if(r.getInt("enabled")!=1||!Instant.parse(r.getString("expires_at")).isAfter(Instant.now())){revoke(token);return Optional.empty();}
                return Optional.of(summary(r));
            }
        }catch(SQLException e){throw failure(e);}
    }

    public synchronized void logout(String token){revoke(token);}
    public synchronized void audit(UserSummary actor,String method,String path,String requestId,String outcome){
        String safePath=path==null?"":path.replaceAll("[\\r\\n]","_");if(safePath.length()>300)safePath=safePath.substring(0,300);
        String safeRequest=requestId==null||!requestId.matches("[A-Za-z0-9._-]{1,100}")?UUID.randomUUID().toString():requestId;
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("INSERT INTO audit_events(actor,role,method,path,outcome,request_id,created_at) VALUES(?,?,?,?,?,?,?)")){
            p.setString(1,actor==null?"local":actor.username());p.setString(2,actor==null?"LOCAL":actor.role());p.setString(3,method);p.setString(4,safePath);p.setString(5,outcome);p.setString(6,safeRequest);p.setString(7,Instant.now().toString());p.executeUpdate();
        }catch(SQLException e){throw failure(e);}
    }
    public synchronized List<AuditEventRecord> auditEvents(int limit){
        int bounded=Math.max(1,Math.min(limit,500));
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT id,actor,role,method,path,outcome,request_id,created_at FROM audit_events ORDER BY id DESC LIMIT ?")){
            p.setInt(1,bounded);try(ResultSet r=p.executeQuery()){List<AuditEventRecord> result=new ArrayList<>();while(r.next())result.add(new AuditEventRecord(r.getLong("id"),r.getString("actor"),r.getString("role"),r.getString("method"),r.getString("path"),r.getString("outcome"),r.getString("request_id"),r.getString("created_at")));return List.copyOf(result);}
        }catch(SQLException e){throw failure(e);}
    }
    public synchronized List<UserSummary> users(){
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT id,username,role,enabled,created_at FROM studio_users ORDER BY username");ResultSet r=p.executeQuery()){
            List<UserSummary> result=new ArrayList<>();while(r.next())result.add(summary(r));return List.copyOf(result);
        }catch(SQLException e){throw failure(e);}
    }

    public synchronized UserSummary createUser(String username,String password,String role){
        if(username==null||!username.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{2,63}"))throw new IllegalArgumentException("Username must be 3-64 characters and use letters, digits, dot, underscore, at-sign, or hyphen.");
        if(password==null||password.length()<12)throw new IllegalArgumentException("Password must contain at least 12 characters.");
        String normalizedRole=role==null?"":role.toUpperCase(Locale.ROOT);if(!Set.of("ADMIN","EDITOR","OPERATOR","VIEWER").contains(normalizedRole))throw new IllegalArgumentException("Role must be ADMIN, EDITOR, OPERATOR, or VIEWER.");
        byte[] salt=new byte[16];random.nextBytes(salt);String id=UUID.randomUUID().toString(),created=Instant.now().toString();
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("INSERT INTO studio_users(id,username,salt,password_hash,role,enabled,created_at) VALUES(?,?,?,?,?,1,?)")){
            p.setString(1,id);p.setString(2,username);p.setBytes(3,salt);p.setBytes(4,hash(password.toCharArray(),salt));p.setString(5,normalizedRole);p.setString(6,created);p.executeUpdate();return new UserSummary(id,username,normalizedRole,true,created);
        }catch(SQLException e){if(e.getMessage()!=null&&e.getMessage().toLowerCase(Locale.ROOT).contains("unique"))throw new IllegalArgumentException("Username already exists.");throw failure(e);}
    }

    public synchronized UserSummary updateUser(String id,String role,Boolean enabled){
        UserSummary current=users().stream().filter(user->user.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("User not found."));
        String nextRole=role==null?current.role():role.toUpperCase(Locale.ROOT);if(!Set.of("ADMIN","EDITOR","OPERATOR","VIEWER").contains(nextRole))throw new IllegalArgumentException("Role must be ADMIN, EDITOR, OPERATOR, or VIEWER.");
        boolean nextEnabled=enabled==null?current.enabled():enabled;
        if("ADMIN".equals(current.role())&&current.enabled()&&(!nextEnabled||!"ADMIN".equals(nextRole))){
            try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM studio_users WHERE id<>? AND role='ADMIN' AND enabled=1")){p.setString(1,id);try(ResultSet r=p.executeQuery()){if(r.next()&&r.getInt(1)==0)throw new IllegalArgumentException("Cannot disable or demote the last active administrator.");}}
            catch(SQLException e){throw failure(e);}
        }
        try(Connection c=connection();PreparedStatement p=c.prepareStatement("UPDATE studio_users SET role=?,enabled=? WHERE id=?")){
            p.setString(1,nextRole);p.setInt(2,nextEnabled?1:0);p.setString(3,id);if(p.executeUpdate()!=1)throw new IllegalArgumentException("User not found.");
            if(!nextEnabled)try(PreparedStatement revoke=c.prepareStatement("DELETE FROM auth_sessions WHERE user_id=?")){revoke.setString(1,id);revoke.executeUpdate();}
            return users().stream().filter(user->user.id().equals(id)).findFirst().orElseThrow();
        }catch(SQLException e){throw failure(e);}
    }

    public boolean allowed(UserSummary user,String path,String method){
        if(user==null||!user.enabled())return false;if("ADMIN".equals(user.role()))return true;if(path.equals("/api/v1/auth/logout"))return true;if(path.startsWith("/api/v1/audit"))return false;
        String verb=method.toUpperCase(Locale.ROOT);boolean execute=path.equals("/api/v1/query/execute")||path.startsWith("/api/v1/ingestion/")||path.matches("/api/v1/plugins/[^/]+/test-connection")&&verb.equals("POST")||path.matches("/api/v1/connections/[^/]+/test")&&verb.equals("POST")||path.equals("/api/v1/executions")&&verb.equals("POST")||path.startsWith("/api/v1/executions/")&&verb.equals("POST");
        boolean design=path.equals("/api/v1/pipelines/import")||path.equals("/api/v1/plugins/reload")||path.startsWith("/api/v1/pipelines/")&&Set.of("PUT","DELETE").contains(verb)||path.equals("/api/v1/pipelines")&&verb.equals("POST")||path.matches("/api/v1/pipelines/[^/]+/publish");
        return switch(user.role()){
            case "EDITOR"->!path.startsWith("/api/v1/auth/users");
            case "OPERATOR"->!design&&!path.startsWith("/api/v1/auth/users")&&(execute||verb.equals("GET"));
            case "VIEWER"->verb.equals("GET")&&!path.startsWith("/api/v1/auth/users");
            default->false;
        };
    }

    private int userCount()throws SQLException{try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM studio_users")){return r.next()?r.getInt(1):0;}}
    private boolean userExists(String username)throws SQLException{try(Connection c=connection();PreparedStatement p=c.prepareStatement("SELECT 1 FROM studio_users WHERE username=?")){p.setString(1,username);try(ResultSet r=p.executeQuery()){return r.next();}}}
    private Connection connection()throws SQLException{Connection c=DriverManager.getConnection("jdbc:sqlite:"+databasePath);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
    private void revoke(String token){if(token==null||token.isBlank())return;try(Connection c=connection();PreparedStatement p=c.prepareStatement("DELETE FROM auth_sessions WHERE token_hash=?")){p.setString(1,sha256(token));p.executeUpdate();}catch(SQLException e){throw failure(e);}}
    private static UserSummary summary(ResultSet r)throws SQLException{return new UserSummary(r.getString("id"),r.getString("username"),r.getString("role"),r.getInt("enabled")==1,r.getString("created_at"));}
    private static byte[] hash(char[] password,byte[] salt){try{PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,KEY_BITS);try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}}catch(GeneralSecurityException e){throw new IllegalStateException("Password hashing is unavailable.",e);}finally{Arrays.fill(password,'\0');}}
    private String randomToken(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String sha256(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static IllegalStateException failure(SQLException e){return new IllegalStateException("Control-plane authentication storage failed: "+e.getMessage(),e);}
}
