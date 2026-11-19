package com.core.docintel.text2cypher;

import java.util.*;
import java.util.regex.*;

/** Checks interpretation and native path evidence independently of syntactic EXPLAIN success. */
final class SemanticEvaluation {
    record Issue(String code,String explanation,boolean retry){}
    record Evaluation(GraphAccess.Rows result,List<Map<String,Object>> coverage,List<Map<String,Object>> paths,List<Issue> issues,String status,int score){}
    static Evaluation merge(Evaluation previous,Evaluation next,int maxRows){
        if(next.status().equals("COMPLETE")&&previous.coverage().stream().filter(p->p.get("status").equals("ANSWERED")).allMatch(p->next.coverage().stream().anyMatch(n->n.get("id").equals(p.get("id"))&&n.get("status").equals("ANSWERED"))))return next;
        Map<String,Map<String,Object>> coverage=new LinkedHashMap<>();previous.coverage().forEach(p->coverage.put(p.get("id").toString(),p));
        next.coverage().forEach(p->coverage.merge(p.get("id").toString(),p,(old,current)->old.get("status").equals("ANSWERED")&&!current.get("status").equals("ANSWERED")?old:current));
        Map<String,Map<String,Object>> rows=new LinkedHashMap<>(),paths=new LinkedHashMap<>(),nodes=new LinkedHashMap<>(),edges=new LinkedHashMap<>();
        for(var evaluation:List.of(previous,next)){
            evaluation.result().rows().forEach(row->rows.putIfAbsent(com.core.docintel.neo4j.GraphPolicy.encode(row),row));
            evaluation.paths().forEach(path->paths.putIfAbsent(com.core.docintel.neo4j.GraphPolicy.encode(path),path));
            evaluation.result().nodes().forEach(node->nodes.put(node.get("id").toString(),node));evaluation.result().edges().forEach(edge->edges.put(edge.get("id").toString(),edge));
        }
        if(rows.values().stream().anyMatch(SemanticEvaluation::hasValues))rows.entrySet().removeIf(e->!hasValues(e.getValue()));
        Set<String> answeredIds=new HashSet<>();coverage.values().stream().filter(p->p.get("status").equals("ANSWERED")).forEach(p->answeredIds.add(p.get("id").toString()));
        List<Issue> issues=new ArrayList<>();
        for(var evaluation:List.of(previous,next))for(var issue:evaluation.issues()){
            if(issue.code().equals("MISSING_PART_RESULTS")&&answeredIds.stream().anyMatch(id->issue.explanation().startsWith("Part "+id+" ")))continue;
            if(issue.code().equals("MISSING_QUESTION_COMPONENT")&&coverage.values().stream().filter(p->p.get("kind").equals("DESCRIPTION")).allMatch(p->p.get("status").equals("ANSWERED")))continue;
            if(!paths.isEmpty()&&Set.of("NO_VERIFIED_PATH","PATH_ENDPOINT_MISMATCH","PATH_DIRECTION_MISMATCH","INVALID_PATH_EVIDENCE").contains(issue.code()))continue;
            if(rows.values().stream().anyMatch(SemanticEvaluation::hasValues)&&Set.of("NO_ROWS","EMPTY_VALUES").contains(issue.code()))continue;
            if(!issues.contains(issue))issues.add(issue);
        }
        boolean capped=rows.size()>maxRows||nodes.size()>256||edges.size()>512;
        if(capped)issues.add(new Issue("MERGED_RESULTS_TRUNCATED","Combined successful results exceed configured display limits; retained data is partial.",false));
        var keptNodes=nodes.values().stream().limit(256).toList();var ids=new HashSet<>();keptNodes.forEach(n->ids.add(n.get("id")));
        var keptEdges=edges.values().stream().filter(e->ids.contains(e.get("source"))&&ids.contains(e.get("target"))).limit(512).toList();
        var result=new GraphAccess.Rows(rows.values().stream().limit(maxRows).toList(),keptNodes,keptEdges,previous.result().truncated()||next.result().truncated()||capped);
        boolean partial=result.truncated()||coverage.size()!=answeredIds.size()||issues.stream().anyMatch(Issue::retry);
        String status=answeredIds.isEmpty()?"EMPTY":partial?"PARTIAL":previous.status().equals("UNVERIFIED")||next.status().equals("UNVERIFIED")?"UNVERIFIED":"COMPLETE";
        return new Evaluation(result,List.copyOf(coverage.values()),List.copyOf(paths.values()),List.copyOf(issues),status,answeredIds.size()*1000+paths.size()*100+(int)Math.min(99,result.rows().stream().filter(SemanticEvaluation::hasValues).count()));
    }
    private static boolean wantsPath(SemanticPlan.Intent intent,SemanticPlan.Hints hints){return (hints.path()||intent.operation().equals("PATH"))&&intent.parts().stream().anyMatch(p->p.kind().equals("PATH")&&p.supported());}
    static List<Issue> validate(SemanticPlan.Draft draft,SemanticPlan.Hints hints,int maxHops){
        List<Issue> issues=new ArrayList<>();var intent=draft.intent();String query=CypherGuard.mask(draft.query().cypher());
        boolean multi=false;var lengths=Pattern.compile("\\[[^\\]]*?\\*\\s*(\\d*)\\s*(?:\\.\\.\\s*(\\d*))?").matcher(query);
        while(lengths.find()){
            String upper=lengths.group(2)==null?lengths.group(1):lengths.group(2);
            int hops=upper.isEmpty()?Integer.MAX_VALUE:parseBound(upper);
            if(hops>maxHops)issues.add(new Issue("UNBOUNDED_PATH","Use a literal variable-length upper bound no greater than "+maxHops+"; do not remove graph filters.",true));
            if(hops>1)multi=true;
        }
        // Fixed-length paths can also legitimately contain multiple relationships.
        String pathFragment=query;
        if(!intent.pathColumn().isBlank()){
            String variable=intent.pathColumn();var alias=Pattern.compile("(?i)\\b([A-Za-z][A-Za-z0-9_]*)\\s+AS\\s+"+Pattern.quote(intent.pathColumn())+"\\b").matcher(query);if(alias.find())variable=alias.group(1);
            var assigned=Pattern.compile("(?is)\\b"+Pattern.quote(variable)+"\\s*=\\s*(.*?)(?=\\b(?:WHERE|RETURN|WITH|MATCH|OPTIONAL|UNWIND)\\b|$)").matcher(query);if(assigned.find())pathFragment=assigned.group(1);
        }
        boolean fixedMulti=Pattern.compile("[-<]\\s*\\[").matcher(pathFragment).results().count()>1;
        if(wantsPath(intent,hints)){
            if(!hints.subject().isBlank()){
                var returned=Pattern.compile("(?i)\\bRETURN\\b").matcher(query);String predicates=returned.find()?query.substring(0,returned.start()):query;
                boolean scoped=draft.query().parameters().entrySet().stream().anyMatch(e->e.getValue() instanceof String text&&SchemaView.matches(text,hints.subject())&&Pattern.compile("\\$"+Pattern.quote(e.getKey())+"(?![A-Za-z0-9_])").matcher(predicates).find());
                if(!scoped)issues.add(new Issue("SUBJECT_SCOPE_REQUIRED","The journey specifies a subject/context in questionHints.subject, but no matching parameter is referenced before RETURN. Add a schema-supported subject predicate; do not answer with a generic endpoint path. If that scope cannot be expressed, mark the path part unsupported and explain the limitation without inventing schema.",true));
            }
            if(Pattern.compile("(?i)\\b(?:nodes|relationships)\\s*\\(\\s*\\[").matcher(query).find())issues.add(new Issue("INVALID_PATH_CONSTRUCTION","nodes() and relationships() require a native path, not a list of endpoint nodes. Assign a path variable in MATCH and RETURN that variable directly AS the declared pathColumn; do not build or collect endpoint lists as paths.",true));
            if(!intent.operation().equals("PATH"))issues.add(new Issue("INTENT_MISMATCH","The question requests traversal between endpoints, not direct adjacency or a simple lookup.",true));
            if(!multi&&!fixedMulti)issues.add(new Issue("SINGLE_HOP_INTERPRETATION","A single relationship does not implement the requested multi-hop/sub-route traversal. Generate a bounded path and return the native path.",true));
            if(intent.pathColumn().isBlank())issues.add(new Issue("PATH_EVIDENCE_REQUIRED","Return the complete native Neo4j path as a column and declare pathColumn. Lists of names or successful syntax checks cannot prove traversal order.",true));
            if(intent.explicit()&&(!intent.anchors().stream().anyMatch(a->a.role().equals("SUBROUTE_START"))||!intent.anchors().stream().anyMatch(a->a.role().equals("SUBROUTE_END"))))issues.add(new Issue("ENDPOINT_ANCHORS_REQUIRED","Declare schema-backed anchors for both sub-route endpoints, distinct from overall journey endpoints.",true));
            if(!hints.betweenStart().isEmpty()&&!(pair(intent.subrouteStart(),intent.subrouteEnd(),hints.betweenStart(),hints.betweenEnd())))issues.add(new Issue("SUBROUTE_ENDPOINT_MISMATCH","Use requiredInterpretation.subrouteStart/subrouteEnd from the between clause, separately from overall journey endpoints. Adding a subject constraint must not change the requested sub-route or its endpoint anchors/parameters.",true));
        }
        if(hints.direct()&&multi)issues.add(new Issue("DIRECT_ADJACENCY_REQUIRED","The question explicitly requests direct neighbors, not a multi-hop expansion.",true));
        if(hints.aggregate()&&!Pattern.compile("(?i)\\b(count|sum|avg|min|max|size|length)\\s*\\(").matcher(query).find())issues.add(new Issue("AGGREGATION_REQUIRED","The question requests an aggregation; return the requested computed value instead of only listing matches.",true));
        if(intent.operation().equals("FILTER")&&!Pattern.compile("(?i)\\bWHERE\\b|\\([^)]*\\{[^}]*\\}").matcher(query).find())issues.add(new Issue("FILTER_PREDICATE_REQUIRED","The plan requests filtering but has no WHERE or node-property predicate.",true));
        return List.copyOf(issues);
    }
    private static int parseBound(String value){try{return Integer.parseInt(value);}catch(NumberFormatException invalid){return Integer.MAX_VALUE;}}
    private static boolean pair(String a,String b,String c,String d){return SchemaView.matches(a,c)&&SchemaView.matches(b,d)||SchemaView.matches(a,d)&&SchemaView.matches(b,c);}
    static Evaluation evaluate(SemanticPlan.Draft draft,SemanticPlan.Hints hints,SchemaView schema,GraphAccess.Rows raw,List<Map<String,Object>> resolutions,int maxRows){
        var intent=draft.intent();List<Issue> issues=new ArrayList<>();List<Map<String,Object>> paths=new ArrayList<>();List<Map<String,Object>> projected=new ArrayList<>();
        boolean pathRequested=wantsPath(intent,hints);
        if(pathRequested){
            for(var nativePath:raw.paths())if(nativePath.column().equals(intent.pathColumn())){
                var path=verifyPath(nativePath,intent,hints,resolutions,issues);if(path==null)continue;paths.add(path);
                var selected=(List<Map<String,Object>>)path.get("selectedNodes");
                if(hints.aggregate())continue;
                int position=0;for(var node:selected){
                    if(projected.size()>=maxRows)break;
                    Map<String,Object> row=new LinkedHashMap<>();row.put("pathIndex",paths.size());row.put("position",++position);row.put("entity",display(node));row.put("node",node);row.put("pathStart",path.get("start"));row.put("pathEnd",path.get("end"));
                    if(nativePath.rowIndex()<raw.rows().size())for(var part:intent.parts())if(!part.kind().equals("PATH")&&part.supported())for(String col:part.resultColumns())if(raw.rows().get(nativePath.rowIndex()).containsKey(col))row.put(col,raw.rows().get(nativePath.rowIndex()).get(col));
                    projected.add(Collections.unmodifiableMap(row));
                }
                if(selected.isEmpty())projected.add(Map.of("pathIndex",paths.size(),"pathStart",path.get("start"),"pathEnd",path.get("end"),"intermediateNodes",List.of()));
            }
            if(paths.isEmpty())issues.add(new Issue("NO_VERIFIED_PATH","No native path matched the requested sub-route endpoints and direction. Do not report an unverified list as an ordered route.",true));
        }
        boolean actionable=intent.parts().stream().anyMatch(p->p.supported()&&(!p.kind().equals("DESCRIPTION")||schema.descriptiveEvidence(p.schemaEvidence())));
        if(raw.rows().stream().noneMatch(SemanticEvaluation::hasValues))issues.add(new Issue(raw.rows().isEmpty()?"NO_ROWS":"EMPTY_VALUES","The read returned no populated values. Check names/aliases, resolved candidates, direction and filters without inventing labels, relationships or data.",actionable));
        if(raw.truncated())issues.add(new Issue("TRUNCATED_RESULTS","The result was capped; narrow the query or return the required path/aggregation without dropping necessary information.",true));
        List<Map<String,Object>> coverage=new ArrayList<>();int answered=0;
        for(var part:intent.parts()){
            boolean supported=part.supported();String reason=part.reason();
            if(part.kind().equals("DESCRIPTION")&&!schema.descriptiveEvidence(part.schemaEvidence())){supported=false;reason="The available graph schema has no cited non-identity attributes substantiating this descriptive request.";}
            if(!part.schemaEvidence().stream().allMatch(schema::hasEvidence)){supported=false;reason="The proposed schema evidence is not present in the available schema.";}
            boolean present=part.kind().equals("PATH")?!paths.isEmpty():part.resultColumns().isEmpty()?(!intent.explicit()&&raw.rows().stream().anyMatch(SemanticEvaluation::hasValues)):part.resultColumns().stream().allMatch(col->raw.rows().stream().anyMatch(row->hasValues(row.get(col))));
            String status=supported&&present?"ANSWERED":"UNANSWERED";if(status.equals("ANSWERED"))answered++;
            if(supported&&!present){reason="No supporting result values were retrieved for this part.";issues.add(new Issue("MISSING_PART_RESULTS","Part "+part.id()+" has schema support but no corresponding result values; retain other successful parts.",true));}
            if(!supported&&reason.isBlank())reason="This part is not established by the available graph schema/results.";
            coverage.add(Map.of("id",part.id(),"request",part.request(),"kind",part.kind(),"status",status,"reason",reason,"schemaEvidence",part.schemaEvidence()));
        }
        if(hints.descriptive()&&intent.parts().stream().noneMatch(p->p.kind().equals("DESCRIPTION"))){
            coverage.add(Map.of("id","description","request",hints.descriptiveRequest(),"kind","DESCRIPTION","status","UNANSWERED","reason","The requested descriptive information was not supported by the retrieval plan/results.","schemaEvidence",List.of()));
            if(schema.anyDescriptiveEvidence())issues.add(new Issue("MISSING_QUESTION_COMPONENT","The question includes a descriptive component which the plan ignored. Assess actual attribute support and retain the already answered graph parts.",true));
        }
        // Do not pass an unverified route to an answer model as if it were the requested path.
        List<Map<String,Object>> outputRows=pathRequested&&!hints.aggregate()?projected:raw.rows();
        if(pathRequested&&paths.isEmpty()){
            List<Map<String,Object>> safe=new ArrayList<>();
            for(var row:raw.rows()){Map<String,Object> values=new LinkedHashMap<>();for(var part:intent.parts())if(!part.kind().equals("PATH")&&coverage.stream().anyMatch(c->c.get("id").equals(part.id())&&c.get("status").equals("ANSWERED")))for(String col:part.resultColumns())if(row.containsKey(col))values.put(col,row.get(col));if(hasValues(values))safe.add(values);}
            outputRows=safe;
        }
        boolean truncated=raw.truncated();int selectedCount=paths.stream().mapToInt(p->((List<?>)p.get("selectedNodes")).size()).sum();
        if(pathRequested&&!hints.aggregate()&&selectedCount>projected.size()){truncated=true;issues.add(new Issue("PROJECTED_PATH_TRUNCATED","The ordered path projection exceeds the configured row limit; this result is partial.",true));}
        boolean incomplete=coverage.stream().anyMatch(c->!c.get("status").equals("ANSWERED"))||issues.stream().anyMatch(Issue::retry);
        String status=answered==0?"EMPTY":incomplete?"PARTIAL":intent.explicit()?"COMPLETE":"UNVERIFIED";
        return new Evaluation(new GraphAccess.Rows(List.copyOf(outputRows),raw.nodes(),raw.edges(),truncated,raw.paths()),List.copyOf(coverage),List.copyOf(paths),List.copyOf(issues),status,answered*1000+paths.size()*100+(int)Math.min(99,outputRows.stream().filter(SemanticEvaluation::hasValues).count()));
    }
    private static Map<String,Object> verifyPath(GraphAccess.NativePath path,SemanticPlan.Intent intent,SemanticPlan.Hints hints,List<Map<String,Object>> resolutions,List<Issue> issues){
        var nodes=new ArrayList<>(path.nodes());var edges=new ArrayList<>(path.edges());
        if(nodes.size()<2||edges.size()!=nodes.size()-1){issues.add(new Issue("INVALID_PATH_EVIDENCE","Native path cardinalities are inconsistent.",true));return null;}
        boolean journey=!intent.journeyStart().isBlank()&&!intent.journeyEnd().isBlank()&&nodeMatches(nodes.getFirst(),intent.journeyEnd(),"JOURNEY_END",resolutions)&&nodeMatches(nodes.getLast(),intent.journeyStart(),"JOURNEY_START",resolutions);
        if(journey){Collections.reverse(nodes);Collections.reverse(edges);}
        String start=intent.subrouteStart().isBlank()?hints.betweenStart():intent.subrouteStart(),end=intent.subrouteEnd().isBlank()?hints.betweenEnd():intent.subrouteEnd();
        int a=find(nodes,start,"SUBROUTE_START",resolutions),b=find(nodes,end,"SUBROUTE_END",resolutions);
        if(a<0||b<0||a==b){issues.add(new Issue("PATH_ENDPOINT_MISMATCH","The returned native path does not contain two distinct unambiguous requested sub-route endpoints; constrain identity/group rather than guessing.",true));return null;}
        boolean fullJourney=nodeMatches(nodes.getFirst(),intent.journeyStart(),"JOURNEY_START",resolutions)&&nodeMatches(nodes.getLast(),intent.journeyEnd(),"JOURNEY_END",resolutions);
        if(a>b&&!fullJourney){Collections.reverse(nodes);Collections.reverse(edges);a=find(nodes,start,"SUBROUTE_START",resolutions);b=find(nodes,end,"SUBROUTE_END",resolutions);}
        int from=Math.min(a,b),to=Math.max(a,b);var subNodes=new ArrayList<>(nodes.subList(from,to+1));var subEdges=new ArrayList<>(edges.subList(from,to));
        for(int i=0;i<subEdges.size();i++){
            var edge=subEdges.get(i);String left=Objects.toString(subNodes.get(i).get("id")),right=Objects.toString(subNodes.get(i+1).get("id"));
            boolean out=left.equals(edge.get("source"))&&right.equals(edge.get("target")),in=left.equals(edge.get("target"))&&right.equals(edge.get("source"));
            if(!out&&!in||intent.direction().equals("OUT")&&!out||intent.direction().equals("IN")&&!in){issues.add(new Issue("PATH_DIRECTION_MISMATCH","The native path does not follow the declared/requested traversal direction.",true));return null;}
        }
        boolean exclude=intent.intermediateOnly()||hints.intermediateOnly();List<Map<String,Object>> selected=exclude?new ArrayList<>(subNodes.subList(1,subNodes.size()-1)):subNodes;
        return Map.of("start",display(subNodes.getFirst()),"end",display(subNodes.getLast()),"hopCount",subEdges.size(),"direction",intent.direction(),"endpointsExcluded",exclude,"orderedNodes",List.copyOf(subNodes),"selectedNodes",List.copyOf(selected));
    }
    private static int find(List<Map<String,Object>> nodes,String mention,String role,List<Map<String,Object>> resolutions){if(mention.isBlank())return -1;int found=-1;for(int i=0;i<nodes.size();i++)if(nodeMatches(nodes.get(i),mention,role,resolutions)){if(found>=0)return -2;found=i;}return found;}
    private static boolean nodeMatches(Map<String,Object> node,String mention,String role,List<Map<String,Object>> resolutions){
        if(mention.isBlank())return false;
        boolean resolved=false;for(var resolution:resolutions)if(role.equals(resolution.get("role"))&&SchemaView.matches(mention,resolution.get("mention"))){
            resolved=true;
            for(Object candidate:(List<?>)resolution.get("candidates"))if(candidate instanceof Map<?,?> value&&SchemaView.matches(value.get("value"),mention)&&Objects.equals("n:"+value.get("elementId"),node.get("id")))return true;
        }
        if(resolved)return false;
        Object props=node.get("properties");if(props instanceof Map<?,?> map)for(String key:List.of("name","title","label","identifier","id","code")){Object value=map.get(key);if(value instanceof String&&SchemaView.matches(value,mention))return true;}
        return SchemaView.matches(node.get("label"),mention);
    }
    private static String display(Map<String,Object> node){return Objects.toString(node.getOrDefault("label",node.get("id")),"");}
    static boolean hasValues(Object value){if(value==null)return false;if(value instanceof String text)return !text.isBlank();if(value instanceof Map<?,?> map)return map.values().stream().anyMatch(SemanticEvaluation::hasValues);if(value instanceof Collection<?> items)return items.stream().anyMatch(SemanticEvaluation::hasValues);return true;}
}
