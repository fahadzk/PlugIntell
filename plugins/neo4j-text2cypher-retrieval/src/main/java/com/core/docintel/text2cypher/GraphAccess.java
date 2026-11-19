package com.core.docintel.text2cypher;
import java.util.*;

interface GraphAccess extends AutoCloseable {
    record NativePath(int rowIndex,String column,List<Map<String,Object>> nodes,List<Map<String,Object>> edges){}
    record Rows(List<Map<String,Object>> rows,List<Map<String,Object>> nodes,List<Map<String,Object>> edges,boolean truncated,List<NativePath> paths){
        Rows(List<Map<String,Object>> rows,List<Map<String,Object>> nodes,List<Map<String,Object>> edges,boolean truncated){this(rows,nodes,edges,truncated,List.of());}
    }
    void verify();
    Map<String,Object> schema(int limit);
    Rows query(CypherGuard.Plan plan,int maxRows,int maxBytes);
    void close();
}
