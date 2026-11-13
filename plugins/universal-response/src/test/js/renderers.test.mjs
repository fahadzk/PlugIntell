import test from 'node:test';
import assert from 'node:assert/strict';
import {parseMermaid,chartData,calculate} from '../../main/resources/response-ui/renderers.js';
test('Mermaid flowcharts preserve order and arrows without executing source',()=>{
 const parsed=parseMermaid('flowchart LR\nA[Rawalpindi] -->|train| B[Lahore]\nB --> C[Karachi]');
 assert.deepEqual(parsed.nodes.map(n=>n.label),['Rawalpindi','Lahore','Karachi']);assert.deepEqual(parsed.edges.map(e=>[e.source,e.target]),[['A','B'],['B','C']]);
 assert.throws(()=>parseMermaid('flowchart LR\nwindow.alert(1)'));
});
test('Chart views use actual finite numeric columns only',()=>{
 const chart=chartData([{month:'Jan',revenue:120,cost:80},{month:'Feb',revenue:170,cost:90}]);assert.deepEqual(chart.series,['revenue','cost']);assert.equal(chart.label,'month');assert.equal(chart.rows[1].revenue,170);
 assert.throws(()=>chartData([{label:'a',bad:'text'}]));
});
test('Widget calculations use predefined arithmetic rather than generated code',()=>{
 assert.equal(calculate('sum',['2','3']),5);assert.equal(calculate('multiply',[4,5]),20);assert.throws(()=>calculate('divide',[2,0]));assert.throws(()=>calculate('sum',['alert(1)']));assert.throws(()=>calculate('eval',['1+1']));
});
