package com.core.docintel.plugins;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Local parsing only; rejects malformed input before any database access. */
final class GraphImportFiles {
    private static final ObjectMapper JSON=new ObjectMapper();
    record Csv(List<Map<String,Object>> nodes,List<Map<String,Object>> relationships){}
    static String identifier(String name){if(name==null||!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")||name.equals("ImportedGraphNode"))throw new IllegalArgumentException("Invalid or reserved CSV label/type identifier.");return name;}
    static Csv csv(String raw){
        var records=records(raw);if(records.size()<2)throw new IllegalArgumentException("CSV needs a header and at least one data row.");if(records.size()>100001)throw new IllegalArgumentException("CSV exceeds 100000 data rows.");
        var header=records.getFirst();Set<String> names=new HashSet<>();for(String h:header)if(!names.add(h)||!Set.of("kind","id","label","name","source","target","type","properties").contains(h))throw new IllegalArgumentException("Duplicate or unknown CSV header.");
        if(!names.containsAll(List.of("kind","id")))throw new IllegalArgumentException("CSV requires kind and id columns.");
        List<Map<String,Object>> nodes=new ArrayList<>(),relations=new ArrayList<>();Set<String> ids=new HashSet<>(),relationIds=new HashSet<>();
        for(int i=1;i<records.size();i++){
            var row=records.get(i);if(row.size()!=header.size())throw new IllegalArgumentException("Wrong column count at CSV record "+(i+1)+".");
            Map<String,String> fields=new HashMap<>();for(int j=0;j<header.size();j++)fields.put(header.get(j),row.get(j));
            String kind=fields.get("kind").strip().toLowerCase(Locale.ROOT),id=required(fields,"id");Map<String,Object> props=properties(fields.getOrDefault("properties",""));
            if(!fields.getOrDefault("name","").isEmpty())props.put("name",fields.get("name"));
            if(kind.equals("node")){
                if(!ids.add(id))throw new IllegalArgumentException("Duplicate node ID in CSV.");
                nodes.add(Map.of("id",id,"type",identifier(required(fields,"label")),"properties",props));
            }else if(kind.equals("relationship")){
                if(!relationIds.add(id))throw new IllegalArgumentException("Duplicate relationship ID in CSV.");
                relations.add(Map.of("id",id,"type",identifier(required(fields,"type")),"source",required(fields,"source"),"target",required(fields,"target"),"properties",props));
            }else throw new IllegalArgumentException("CSV kind must be node or relationship at record "+(i+1)+".");
        }
        return new Csv(nodes,relations);
    }
    private static String required(Map<String,String> fields,String key){String s=fields.getOrDefault(key,"").strip();if(s.isEmpty()||s.length()>512)throw new IllegalArgumentException("Missing or oversized CSV "+key+".");return s;}
    private static Map<String,Object> properties(String raw){
        Map<String,Object> result=new LinkedHashMap<>();if(raw.isBlank())return result;
        try{Object value=JSON.readValue(raw,Object.class);if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException();
            for(var entry:map.entrySet()){
                String key=(String)entry.getKey();if(key.isBlank()||Set.of("id","namespace").contains(key))throw new IllegalArgumentException();
                Object v=entry.getValue();if(v instanceof List<?> list){String category=null;for(Object item:list){String next=category(item);if(next==null||category!=null&&!category.equals(next))throw new IllegalArgumentException();category=next;}}
                else if(v!=null&&category(v)==null)throw new IllegalArgumentException();result.put(key,v);
            }return result;
        }catch(Exception e){throw new IllegalArgumentException("CSV properties must be a JSON object of scalar values or homogeneous scalar arrays; id and namespace are reserved.");}
    }
    private static String category(Object v){if(v instanceof String)return "string";if(v instanceof Boolean)return "boolean";if(v instanceof Number n&&Double.isFinite(n.doubleValue()))return "number";return null;}
    static List<List<String>> records(String raw){
        if(raw.startsWith("\uFEFF"))raw=raw.substring(1);
        List<List<String>> records=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(quoted){if(c=='"'){if(i+1<raw.length()&&raw.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);continue;}
            if(c=='"'){if(cell.length()!=0||closed)throw new IllegalArgumentException("Unexpected CSV quote.");quoted=true;continue;}
            if(c==','||c=='\r'||c=='\n'){
                row.add(cell.toString());cell.setLength(0);closed=false;
                if(c!=','){if(!(row.size()==1&&row.getFirst().isEmpty()))records.add(List.copyOf(row));row.clear();if(c=='\r'&&i+1<raw.length()&&raw.charAt(i+1)=='\n')i++;}
            }else{if(closed)throw new IllegalArgumentException("Characters after a closing CSV quote.");cell.append(c);}
            if(records.size()>100001)throw new IllegalArgumentException("CSV exceeds 100000 data rows.");
        }
        if(quoted)throw new IllegalArgumentException("Unterminated CSV quote.");if(!row.isEmpty()||cell.length()>0||closed){row.add(cell.toString());records.add(List.copyOf(row));}
        return records;
    }
    static List<String> statements(String raw){
        List<String> result=new ArrayList<>();StringBuilder current=new StringBuilder();char quote=0;boolean line=false;int block=0;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i),next=i+1<raw.length()?raw.charAt(i+1):0;
            if(line){if(c=='\r'||c=='\n'){line=false;current.append(' ');}continue;}
            if(block>0){if(c=='/'&&next=='*'){block++;i++;}else if(c=='*'&&next=='/'){block--;i++;current.append(' ');}continue;}
            if(quote!=0){current.append(c);if(c=='\\'&&quote!='`'&&next!=0){current.append(next);i++;}else if(c==quote){if(next==quote){current.append(next);i++;}else quote=0;}continue;}
            if(c=='/'&&next=='/'){line=true;i++;continue;}if(c=='/'&&next=='*'){block=1;i++;continue;}
            if(c=='\''||c=='"'||c=='`'){quote=c;current.append(c);continue;}
            if(c==';'){addStatement(result,current);current.setLength(0);}else current.append(c);
        }
        if(quote!=0||block>0)throw new IllegalArgumentException("Unterminated Cypher string, identifier or block comment.");addStatement(result,current);
        if(result.isEmpty())throw new IllegalArgumentException("Cypher file contains no statements.");if(result.size()>10000)throw new IllegalArgumentException("Cypher file exceeds 10000 statements.");return result;
    }
    private static void addStatement(List<String> result,StringBuilder text){String statement=text.toString().strip();if(statement.startsWith("\uFEFF"))statement=statement.substring(1).strip();if(statement.isEmpty())return;if(statement.startsWith(":"))throw new IllegalArgumentException("Cypher shell/browser commands are unsupported; supply ordinary Cypher statements.");result.add(statement);}
}
