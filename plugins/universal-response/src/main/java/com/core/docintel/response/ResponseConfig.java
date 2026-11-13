package com.core.docintel.response;
import java.util.*;
/** Flat configuration follows the plugin manifest and generated application convention. */
public record ResponseConfig(boolean ui,int port,boolean browser,boolean everyResponse,String mode,String theme,
        int maxResponses,int ageMinutes,int maxResponseBytes,long maxStoreBytes,boolean artifacts,boolean remoteMedia,Set<String> renderers) {
    public static final Set<String> TYPES=Set.of("markdown","text","json","xml","code","table","chart","graph","mermaid","svg","html","media","widget");
    static String value(Map<String,Object> map,String key,String fallback){return Objects.toString(map.getOrDefault(key,System.getenv().getOrDefault(key,fallback)),"").strip();}
    static boolean bool(Map<String,Object> map,String key,boolean fallback){String v=value(map,key,""+fallback);if(!Set.of("true","false").contains(v))throw new IllegalArgumentException(key+" must be true or false.");return Boolean.parseBoolean(v);}
    static int number(Map<String,Object> map,String key,int fallback,int min,int max){int n=Integer.parseInt(value(map,key,""+fallback));if(n<min||n>max)throw new IllegalArgumentException(key+" outside allowed range.");return n;}
    public static ResponseConfig from(Map<String,Object> map){
        if(!value(map,"UR_HOST","127.0.0.1").equals("127.0.0.1"))throw new IllegalArgumentException("Universal Response supports loopback host 127.0.0.1 only.");
        String mode=value(map,"UR_OUTPUT_MODE","auto"),theme=value(map,"UR_THEME","system");
        if(!mode.equals("auto")&&!TYPES.contains(mode)&&!Set.of("pdf","docx","xlsx","pptx").contains(mode))throw new IllegalArgumentException("Unsupported UR_OUTPUT_MODE.");
        if(!Set.of("system","light","dark").contains(theme))throw new IllegalArgumentException("UR_THEME must be system, light or dark.");
        Set<String> enabled=new HashSet<>();for(String type:value(map,"UR_RENDERERS",String.join(",",new TreeSet<>(TYPES))).split(",")){if(!TYPES.contains(type.strip()))throw new IllegalArgumentException("Unknown renderer.");enabled.add(type.strip());}
        return new ResponseConfig(bool(map,"UR_UI_ENABLED",true),number(map,"UR_PORT",8765,0,65535),bool(map,"UR_AUTO_OPEN_BROWSER",true),bool(map,"UR_OPEN_EVERY_RESPONSE",false),mode,theme,
            number(map,"UR_MAX_RESPONSES",100,1,1000),number(map,"UR_MAX_AGE_MINUTES",60,1,1440),number(map,"UR_MAX_RESPONSE_BYTES",1048576,1024,8388608),number(map,"UR_MAX_STORE_BYTES",33554432,1048576,268435456),bool(map,"UR_ARTIFACTS_ENABLED",true),bool(map,"UR_ALLOW_REMOTE_MEDIA",false),Set.copyOf(enabled));
    }
}
