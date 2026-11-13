package com.core.docintel.text2cypher;
import java.util.*;

interface GraphAccess extends AutoCloseable {
    record Rows(List<Map<String,Object>> rows,List<Map<String,Object>> nodes,List<Map<String,Object>> edges,boolean truncated){}
    void verify();
    Map<String,Object> schema(int limit);
    Rows query(CypherGuard.Plan plan,int maxRows,int maxBytes);
    void close();
}
