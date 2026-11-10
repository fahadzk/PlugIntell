package com.core.docintel.pipeline.events;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
/** One bounded, independent worker per adapter instance. All subscriptions for an instance are ORed. */
public final class EventDispatcher implements AutoCloseable {
 private final Map<String,Destination> destinations=new LinkedHashMap<>();
 private final int capacity;private final Duration drain;
 private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
 public EventDispatcher(){this(256,Duration.ofSeconds(2));}
 public EventDispatcher(int capacity,Duration drain){if(capacity<2)throw new IllegalArgumentException("Queue capacity must be at least two.");this.capacity=capacity;this.drain=drain;}
 public synchronized void attach(String id,EventAdapter adapter,List<EventFilter> filters){attachLazy(id,()->adapter,filters);}
 public synchronized void attachLazy(String id,java.util.function.Supplier<EventAdapter> factory,List<EventFilter> filters){attachDestination(id,factory,filters,false);}
 /** Native/synchronized console/file I/O must not pin Java 21 processing carriers. */
 public synchronized void attachBlocking(String id,java.util.function.Supplier<EventAdapter> factory,List<EventFilter> filters){attachDestination(id,factory,filters,true);}
 private void attachDestination(String id,java.util.function.Supplier<EventAdapter> factory,List<EventFilter> filters,boolean blocking){if(closed.get())throw new IllegalStateException("Dispatcher is closed.");if(destinations.containsKey(id))throw new IllegalArgumentException("Duplicate adapter instance: "+id);destinations.put(id,new Destination(id,factory,filters.isEmpty()?List.of(EventFilter.all()):List.copyOf(filters),blocking));}
 public synchronized void dispatch(ExecutionEvent event){for(Destination d:destinations.values())if(d.filters.stream().anyMatch(f->f.matches(event)))d.offer(event);}
 public synchronized List<Map<String,Object>> health(){return destinations.values().stream().map(Destination::health).toList();}
 @Override public void close(){
  if(!closed.compareAndSet(false,true))return;
  List<Destination> copy; synchronized(this){copy=List.copyOf(destinations.values());}
  long deadline=System.nanoTime()+drain.toNanos();copy.forEach(d->d.stop(deadline));
  for(Destination d:copy){
   long remaining=deadline-System.nanoTime();
   if(remaining>0)try{d.worker.join(Duration.ofNanos(remaining));}catch(InterruptedException e){Thread.currentThread().interrupt();}
   if(d.worker.isAlive()){d.worker.interrupt();synchronized(d){d.dropped.addAndGet(d.queue.size());d.queue.clear();}}
  }
 }
 private final class Destination{
  final String id;volatile EventAdapter adapter;volatile Map<String,Object> adapterHealth=Map.of("status","STARTING");final java.util.function.Supplier<EventAdapter> factory;final List<EventFilter> filters;final Deque<ExecutionEvent> queue=new ArrayDeque<>();final AtomicLong delivered=new AtomicLong(),dropped=new AtomicLong(),coalesced=new AtomicLong(),failed=new AtomicLong();final Thread worker;volatile boolean stopping;volatile long shutdownDeadline;volatile String lastFailure;
  Destination(String id,java.util.function.Supplier<EventAdapter> factory,List<EventFilter> filters,boolean blocking){this.id=id;this.factory=factory;this.filters=filters;worker=blocking?Thread.ofPlatform().daemon().name("events-"+id).start(this::work):Thread.ofVirtual().name("events-"+id).start(this::work);}
  synchronized void offer(ExecutionEvent e){if(stopping){dropped.incrementAndGet();return;}
   if(e.type().equals("PROGRESS")){var it=queue.descendingIterator();while(it.hasNext()){ExecutionEvent old=it.next();if(old.type().equals("PROGRESS")&&Objects.equals(old.attemptId(),e.attemptId())){it.remove();coalesced.incrementAndGet();break;}}}
   if(queue.size()>=capacity){if(e.terminal()){var it=queue.iterator();boolean removed=false;while(it.hasNext()){if(!it.next().terminal()){it.remove();removed=true;break;}}if(!removed){dropped.incrementAndGet();return;}dropped.incrementAndGet();}else{dropped.incrementAndGet();return;}}
   queue.addLast(e);java.util.concurrent.locks.LockSupport.unpark(worker);
  }
  synchronized void stop(long deadline){shutdownDeadline=deadline;stopping=true;java.util.concurrent.locks.LockSupport.unpark(worker);}
  void work(){
   try{
   try{adapter=factory.get();}catch(Throwable failure){failed.incrementAndGet();lastFailure=failure.getClass().getSimpleName();adapter=new EventAdapter(){public void accept(ExecutionEvent e){throw new IllegalStateException("Adapter initialization failed");}public Map<String,Object> health(){return Map.of("status","DOWN");}};}
   while(true){ExecutionEvent e=null;
    synchronized(this){if(queue.isEmpty()&&stopping)return;if(!queue.isEmpty())e=queue.removeFirst();}
    if(e==null){java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250));if(Thread.currentThread().isInterrupted())return;}
    if(e!=null)try{adapter.accept(e);delivered.incrementAndGet();}catch(Throwable failure){failed.incrementAndGet();lastFailure=failure.getClass().getSimpleName();}
    try{adapterHealth=new LinkedHashMap<>(adapter.health());}catch(Throwable failure){adapterHealth=Map.of("status","UNKNOWN");}
   }
   }finally{
    if(adapter!=null){
     long deadline=stopping?shutdownDeadline:System.nanoTime()+drain.toNanos();
     try{adapter.close(Duration.ofNanos(Math.max(1,deadline-System.nanoTime())));}catch(Throwable failure){failed.incrementAndGet();lastFailure=failure.getClass().getSimpleName();}
     try{adapterHealth=new LinkedHashMap<>(adapter.health());}catch(Throwable failure){adapterHealth=Map.of("status","UNKNOWN");}
    }
   }
  }
  synchronized Map<String,Object> health(){Map<String,Object> m=new LinkedHashMap<>();m.put("id",id);m.put("status",failed.get()>0?"DEGRADED":stopping?"STOPPED":"UP");m.put("queued",queue.size());m.put("delivered",delivered.get());m.put("dropped",dropped.get());m.put("coalesced",coalesced.get());m.put("failed",failed.get());m.put("lastFailure",lastFailure);m.put("adapter",adapterHealth);return m;}
 }
}

