package com.core.docintel.text2cypher;

import com.core.docintel.neo4j.GraphPolicy;
import java.util.*;
import java.util.regex.*;

/** Recover a mistaken technical-ID predicate only from exact, unique database display-name evidence. */
final class SubjectIdentityRepair {
    record Result(SemanticPlan.Draft draft,List<Map<String,Object>> resolutions,List<String> changes){}
    static Result repair(GraphAccess graph,SchemaView schema,SemanticPlan.Draft draft,SemanticPlan.Hints hints,List<Map<String,Object>> original){
        var resolutions=new ArrayList<>(original);var anchors=new ArrayList<>(draft.intent().anchors());var parts=new ArrayList<>(draft.intent().parts());var changes=new ArrayList<String>();String query=draft.query().cypher();
        for(int i=0;i<resolutions.size();i++){
            var resolution=resolutions.get(i);String role=resolution.get("role").toString(),label=resolution.get("label").toString(),oldProperty=resolution.get("property").toString(),parameter=resolution.get("parameter").toString(),mention=resolution.get("mention").toString();
            if(!role.equals("SUBJECT")||!mention.equalsIgnoreCase(hints.subject())||!((List<?>)resolution.get("candidates")).isEmpty()||!Set.of("id","key","uuid","identifier","code").contains(oldProperty.toLowerCase(Locale.ROOT)))continue;
            if(!List.of(label,oldProperty,parameter).stream().allMatch(s->s.matches("[A-Za-z][A-Za-z0-9_]*")))continue;
            Pattern predicate=Pattern.compile("(\\(\\s*[A-Za-z][A-Za-z0-9_]*\\s*:\\s*"+Pattern.quote(label)+"\\s*\\{\\s*)("+Pattern.quote(oldProperty)+")(\\s*:\\s*\\$"+Pattern.quote(parameter)+"\\s*\\}\\s*\\))");
            if(!predicate.matcher(CypherGuard.mask(query)).find())continue;
            List<Map<String,Object>> alternatives=new ArrayList<>();Set<String> ids=new HashSet<>();boolean ambiguous=false;
            for(String property:List.of("name","title","label"))if(schema.has(label,property)&&!property.equals(oldProperty)){
                var anchor=new SemanticPlan.Anchor(role,mention,label,property,parameter);
                var intent=copy(draft.intent(),List.of(anchor),draft.intent().parts());
                var found=schema.resolve(graph,intent);
                if(found.isEmpty())continue;
                var values=(List<Map<String,Object>>)found.getFirst().get("candidates");
                // Partial descriptive matches never justify changing the identity field.
                var exact=values.stream().filter(c->mention.equalsIgnoreCase(Objects.toString(c.get("value"),""))).toList();
                if(exact.size()>1){ambiguous=true;continue;}
                if(exact.size()==1){var candidate=new LinkedHashMap<>(found.getFirst());candidate.put("candidates",exact);alternatives.add(candidate);ids.add(exact.getFirst().get("elementId").toString());}
            }
            if(ambiguous||ids.size()!=1||alternatives.isEmpty())continue;
            var selected=new LinkedHashMap<>(alternatives.getFirst());String property=selected.get("property").toString();
            var matches=predicate.matcher(CypherGuard.mask(query));StringBuilder updated=new StringBuilder();int offset=0;
            while(matches.find()){updated.append(query,offset,matches.start(2)).append(property);offset=matches.end(2);}updated.append(query.substring(offset));query=updated.toString();
            selected.put("originalProperty",oldProperty);selected.put("resolutionMethod","EXACT_UNIQUE_DISPLAY_NAME");resolutions.set(i,selected);
            for(int j=0;j<anchors.size();j++){var anchor=anchors.get(j);if(anchor.role().equals(role)&&anchor.label().equals(label)&&anchor.property().equals(oldProperty)&&anchor.parameter().equals(parameter))anchors.set(j,new SemanticPlan.Anchor(role,anchor.mention(),label,property,parameter));}
            for(int j=0;j<parts.size();j++){var part=parts.get(j);var evidence=part.schemaEvidence().stream().map(e->e.equals(label+"."+oldProperty)?label+"."+property:e).toList();parts.set(j,new SemanticPlan.Part(part.id(),part.request(),part.kind(),part.supported(),part.resultColumns(),evidence,part.reason()));}
            changes.add("Subject identity predicate corrected from "+label+"."+oldProperty+" to "+label+"."+property+" using one exact unique database display-name match; original identity lookup had no candidates.");
        }
        var params=GraphPolicy.JSON.createArrayNode();draft.query().parameters().forEach((name,value)->{var p=params.addObject();p.put("name",name);p.set("value",GraphPolicy.JSON.valueToTree(value));});
        var validated=CypherGuard.validate(GraphPolicy.JSON.valueToTree(Map.of("cypher",query,"parameters",params)));
        return new Result(new SemanticPlan.Draft(validated,copy(draft.intent(),anchors,parts)),List.copyOf(resolutions),List.copyOf(changes));
    }
    private static SemanticPlan.Intent copy(SemanticPlan.Intent i,List<SemanticPlan.Anchor> anchors,List<SemanticPlan.Part> parts){return new SemanticPlan.Intent(i.operation(),i.ordered(),i.intermediateOnly(),i.direction(),i.subrouteStart(),i.subrouteEnd(),i.journeyStart(),i.journeyEnd(),i.pathColumn(),List.copyOf(parts),List.copyOf(anchors),i.explicit());}
}
