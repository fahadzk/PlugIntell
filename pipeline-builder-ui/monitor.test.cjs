const {test}=require('node:test');
const assert=require('node:assert/strict');
const {MonitorState,progressPercent,progressText,readSse,graphLayout}=require('./monitor.js');
const snapshot=(extra={})=>({runId:'run',descriptor:{nodes:[{id:'a',pluginId:'fixture',label:'Source'},{id:'b',pluginId:'next',label:'Next'}],edges:[{from:'a',to:'b'}]},sequence:0,status:'WAITING',nodes:{},events:[],...extra});
const event=(sequence,type,extra={})=>({schemaVersion:1,runId:'run',sequence,eventId:'e'+sequence,type,timestamp:'2026-11-06T12:00:00Z',nodeId:'a',attempt:1,attemptId:'attempt-1',payload:{},...extra});

test('retry resets current progress while retaining the previous attempt and ignoring late old progress',()=>{
 const state=new MonitorState().reset(snapshot());state.receiveEvent(event(1,'ATTEMPT_STARTED'));state.receiveEvent(event(2,'PROGRESS',{payload:{completed:7,total:10,unit:'pages'}}));state.receiveEvent(event(3,'ATTEMPT_FAILED'));state.receiveEvent(event(4,'NODE_RETRYING'));assert.equal(state.nodes.get('a').progress,null);
 state.receiveEvent(event(5,'ATTEMPT_STARTED',{attempt:2,attemptId:'attempt-2'}));state.receiveEvent(event(6,'PROGRESS',{payload:{completed:9,total:10}}));assert.equal(state.nodes.get('a').progress,null);
 state.receiveEvent(event(7,'PROGRESS',{attempt:2,attemptId:'attempt-2',payload:{completed:1,total:10}}));const node=state.nodes.get('a');assert.equal(node.attempt,2);assert.equal(node.progress.completed,1);assert.equal(node.attempts.get('attempt-1').status,'FAILED');assert.equal(node.attempts.get('attempt-1').progress.completed,7);
});
test('timeout remains visible after worker termination and ignores subsequent progress',()=>{
 const state=new MonitorState().reset(snapshot());state.receiveEvent(event(1,'ATTEMPT_STARTED'));state.receiveEvent(event(2,'ATTEMPT_TIMEOUT',{durationMillis:100}));state.receiveEvent(event(3,'ATTEMPT_TERMINATED'));state.receiveEvent(event(4,'PROGRESS',{payload:{completed:99,total:100}}));const node=state.nodes.get('a');assert.equal(node.status,'TIMED_OUT');assert.equal(node.progress,null);assert.equal(node.attempts.get('attempt-1').status,'TIMED_OUT');assert.equal(node.attempts.get('attempt-1').terminated,true);
});
test('task termination is independent from successful/failed outcomes and supports both emission orders',()=>{
 for(const outcome of ['SUCCEEDED','FAILED'])for(const terminationFirst of [true,false]){const state=new MonitorState().reset(snapshot());state.receiveEvent(event(1,'ATTEMPT_STARTED'));const ordered=terminationFirst?['ATTEMPT_TERMINATED','ATTEMPT_'+outcome,'NODE_'+outcome]:['ATTEMPT_'+outcome,'NODE_'+outcome,'ATTEMPT_TERMINATED'];ordered.forEach((type,index)=>state.receiveEvent(event(index+2,type)));const node=state.nodes.get('a');assert.equal(node.status,outcome);assert.equal(node.attempts.get('attempt-1').status,outcome);assert.equal(node.attempts.get('attempt-1').terminationKnown,true);assert.equal(node.terminationKnown,true)}
});
test('node cancellation does not cancel the whole run until its lifecycle event arrives',()=>{
 const state=new MonitorState().reset(snapshot());state.receiveEvent(event(1,'RUN_STARTED',{nodeId:null,attemptId:null}));state.receiveEvent(event(2,'ATTEMPT_STARTED'));state.receiveEvent(event(3,'CANCELLATION_REQUESTED'));assert.equal(state.status,'RUNNING');assert.equal(state.nodes.get('a').status,'CANCELLATION_REQUESTED');state.receiveEvent(event(4,'CANCELLATION_REQUESTED',{nodeId:null,attemptId:null}));state.receiveEvent(event(5,'NODE_CANCELLED'));assert.equal(state.nodes.get('a').status,'CANCELLED');assert.equal(state.terminal,false);state.receiveEvent(event(6,'RUN_CANCELLED',{nodeId:null,attemptId:null}));assert.equal(state.terminal,true);assert.equal(state.nodes.get('b').status,'PENDING');
});
test('replayed events and another run cannot move the cursor or duplicate timeline entries',()=>{
 const state=new MonitorState().reset(snapshot());assert.equal(state.receiveEvent(event(2,'NODE_STARTED')),true);assert.equal(state.receiveEvent(event(2,'NODE_STARTED')),false);assert.equal(state.receiveEvent(event(1,'NODE_STARTED')),false);assert.equal(state.receiveEvent(event(3,'NODE_FAILED',{runId:'another'})),false);assert.equal(state.cursor,2);assert.equal(state.events.length,1);
});
test('snapshot canonical state wins over old history and reconstructs attempts',()=>{
 const state=new MonitorState().reset(snapshot({sequence:10,status:'RUNNING',nodes:{a:{status:'RUNNING',attempt:2,attemptId:'attempt-2',progress:{completed:3,total:10}}},events:[event(1,'ATTEMPT_STARTED'),event(2,'ATTEMPT_FAILED')]}));assert.equal(state.nodes.get('a').status,'RUNNING');assert.equal(state.nodes.get('a').progress.completed,3);assert.equal(state.nodes.get('a').attempts.get('attempt-1').status,'FAILED');assert.equal(state.nodes.get('a').attempts.get('attempt-2').progress.completed,3);
 state.reset(snapshot({sequence:30,status:'SUCCEEDED',nodes:{a:{status:'SUCCEEDED'}},events:[]}));assert.equal(state.cursor,30);assert.equal(state.events.length,0);assert.equal(state.terminal,true);assert.equal(state.nodes.get('a').attempts.size,0);
});

test('safe input/output metadata survives snapshots and lifecycle events without history overwrites',()=>{
 const state=new MonitorState().reset(snapshot({sequence:3,nodes:{a:{status:'RUNNING',inputSummary:{type:'TextDocument',characters:42}}},events:[event(1,'NODE_STARTED',{payload:{inputSummary:{type:'old'}}})]}));assert.equal(state.nodes.get('a').inputSummary.type,'TextDocument');state.receiveEvent(event(4,'NODE_STARTED',{payload:{inputSummary:{type:'ChunkCollection',chunks:0}}}));state.receiveEvent(event(5,'NODE_SUCCEEDED',{payload:{outputSummary:{type:'EmbeddingCollection',vectors:4,dimensions:3}}}));assert.equal(state.nodes.get('a').inputSummary.chunks,0);assert.deepEqual(state.snapshot().nodes.a.outputSummary,{type:'EmbeddingCollection',vectors:4,dimensions:3});
});

test('hidden domain events advance the cursor without changing progress or visible timeline counts',()=>{
 const state=new MonitorState().reset(snapshot());state.receiveEvent(event(1,'ATTEMPT_STARTED'));state.receiveEvent(event(2,'PROGRESS',{payload:{completed:2,total:10}}));assert.equal(state.receiveEvent(event(3,'PROGRESS',{visible:false,payload:{completed:9,total:10}})),true);state.receiveEvent(event(4,'PLUGIN_MESSAGE',{visible:false,message:'hidden'}));assert.equal(state.cursor,4);assert.equal(state.nodes.get('a').progress.completed,2);assert.equal(state.nodes.get('a').attempts.get('attempt-1').progress.completed,2);assert.equal(state.events.length,2);assert.equal(state.receiveEvent(event(3,'PROGRESS')),false);
});

test('hidden lifecycle events retain authoritative status and summaries while remaining absent from history',()=>{
 const state=new MonitorState().reset(snapshot());state.receiveEvent(event(1,'NODE_STARTED',{visible:false,payload:{inputSummary:{type:'File',bytes:20}}}));state.receiveEvent(event(2,'ATTEMPT_STARTED',{visible:false}));state.receiveEvent(event(3,'ATTEMPT_SUCCEEDED',{visible:false}));state.receiveEvent(event(4,'NODE_SUCCEEDED',{visible:false,payload:{outputSummary:{type:'TextDocument',characters:20}}}));state.receiveEvent(event(5,'RUN_SUCCEEDED',{visible:false,nodeId:null,attemptId:null}));assert.equal(state.status,'SUCCEEDED');assert.equal(state.nodes.get('a').status,'SUCCEEDED');assert.equal(state.nodes.get('a').inputSummary.bytes,20);assert.equal(state.nodes.get('a').outputSummary.characters,20);assert.equal(state.nodes.get('a').attempts.get('attempt-1').status,'SUCCEEDED');assert.equal(state.cursor,5);assert.equal(state.events.length,0);
});

test('snapshot replay honors visibility markers and preserves projected canonical node progress',()=>{
 const state=new MonitorState().reset(snapshot({sequence:5,status:'RUNNING',nodes:{a:{status:'RUNNING',attempt:1,attemptId:'attempt-1',progress:{completed:2,total:10}}},events:[event(1,'ATTEMPT_STARTED',{visible:false}),event(2,'PROGRESS',{payload:{completed:2,total:10}}),event(3,'PROGRESS',{visible:false,payload:{completed:9,total:10}}),event(4,'PLUGIN_MESSAGE',{visible:false}),event(5,'NODE_STARTED',{visible:false})]}));assert.equal(state.cursor,5);assert.equal(state.nodes.get('a').progress.completed,2);assert.equal(state.nodes.get('a').attempts.get('attempt-1').progress.completed,2);assert.deepEqual(state.events.map(item=>item.sequence),[2]);assert.equal(state.snapshot().events.length,1);
});
test('messages never change lifecycle state and history retention stays bounded',()=>{
 const state=new MonitorState().reset(snapshot());for(let sequence=1;sequence<=520;sequence++)state.receiveEvent(event(sequence,'PLUGIN_MESSAGE',{payload:{status:'SUCCEEDED'}}));assert.equal(state.status,'WAITING');assert.equal(state.nodes.get('a').status,'PENDING');assert.equal(state.events.length,500);assert.equal(state.events[0].sequence,21);
});
test('progress uses actual totals, clamps bars and keeps unknown totals indeterminate',()=>{
 assert.equal(progressPercent({completed:2,total:8}),25);assert.equal(progressPercent({completed:30,total:10}),100);assert.equal(progressPercent({completed:2}),null);assert.equal(progressPercent({completed:2,total:0}),null);assert.equal(progressPercent(null),null);assert.match(progressText(null,'RUNNING'),/not reported/);assert.match(progressText({completed:2,unit:'pages'},'RUNNING'),/total unknown/);assert.match(progressText(null,'SUCCEEDED'),/Completed.*not reported/);assert.equal(progressText({completed:4,total:10,unit:'pages'},'SUCCEEDED'),'Completed · 4 / 10 pages');assert.equal(progressPercent({completed:null,total:10}),null);assert.equal(progressPercent({completed:4,total:null}),null);
});
test('water layout preserves branch and fan-in graph topology',()=>{
 const descriptor={nodes:['a','b','c','d'].map(id=>({id})),edges:[{from:'a',to:'b'},{from:'a',to:'c'},{from:'b',to:'d'},{from:'c',to:'d'}]},layout=graphLayout(descriptor);assert.equal(layout.positions.size,4);assert.equal(layout.positions.get('b').x,layout.positions.get('c').x);assert.notEqual(layout.positions.get('b').y,layout.positions.get('c').y);assert.ok(layout.positions.get('a').x<layout.positions.get('b').x);assert.ok(layout.positions.get('d').x>layout.positions.get('c').x);
});
test('fetch-SSE parser handles UTF-8, multiline data and CRLF split at arbitrary byte boundaries',async()=>{
 const encoded=new TextEncoder().encode(': heartbeat\r\nevent: execution\r\nid: 12\r\ndata: {"message":"café",\r\ndata: "sequence":12}\r\n\r\nevent: gap\ndata: {"sequence":20}\n\n'),frames=[];
 const body=new ReadableStream({start(controller){for(let i=0;i<encoded.length;i++)controller.enqueue(encoded.slice(i,i+1));controller.close()}});await readSse({body},frame=>frames.push(frame));assert.equal(frames.length,2);assert.equal(frames[0].event,'execution');assert.equal(frames[0].id,'12');assert.equal(JSON.parse(frames[0].data).message,'café');assert.equal(frames[1].event,'gap');
});
test('fetch-SSE parser discards interrupted frames so reconnect can replay them safely',async()=>{
 const frames=[],body=new ReadableStream({start(controller){controller.enqueue(new TextEncoder().encode('id: 1\ndata: complete\n\nid: 2\ndata: incomplete'));controller.close()}});await readSse({body},frame=>frames.push(frame));assert.deepEqual(frames.map(frame=>frame.id),['1']);
});

test('fetch-SSE can release a continuous server stream when its consumer explicitly requests a stop',async()=>{
 let cancelled=false;const body=new ReadableStream({start(controller){controller.enqueue(new TextEncoder().encode('event: execution\ndata: {"type":"RUN_SUCCEEDED"}\n\n'))},cancel(){cancelled=true}});const frames=[];await readSse({body},frame=>{frames.push(frame);return false});assert.equal(frames.length,1);assert.equal(cancelled,true);
});
