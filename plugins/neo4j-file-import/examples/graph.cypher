// $namespace comes from NEO4J_IMPORT_NAMESPACE.
MERGE (p:ImportedGraphNode:Person {namespace:$namespace,id:'p1'}) SET p.name='Alice';
MERGE (o:ImportedGraphNode:Organization {namespace:$namespace,id:'o1'}) SET o.name='Acme';
MATCH (p:ImportedGraphNode {namespace:$namespace,id:'p1'}),
      (o:ImportedGraphNode {namespace:$namespace,id:'o1'})
MERGE (p)-[:WORKS_FOR {namespace:$namespace,id:'r1'}]->(o);
