package com.core.docintel.pipeline.builder;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.util.concurrent.*;

/** Executes one plugin operation with bounded retries, interruptible backoff, and a hard wait deadline. */
@Singleton
public final class NodeTaskRunner implements AutoCloseable {
    @FunctionalInterface public interface Listener { void event(String state,String message,long durationMillis); default void attemptEvent(int attempt,String state,String message,long durationMillis){event(state,message,durationMillis);} }
    private final ExecutorService calls=Executors.newVirtualThreadPerTaskExecutor();

    public <T>T execute(NodeExecutionPolicy policy,Callable<T> operation,Listener listener)throws InterruptedException{
        return executeAttempts(policy,attempt->operation,listener);
    }
    public <T>T executeAttempts(NodeExecutionPolicy policy,java.util.function.IntFunction<Callable<T>> operation,Listener listener)throws InterruptedException{
        for(int attempt=0;;attempt++){
            int number=attempt+1;
            Listener scoped=(state,message,duration)->listener.attemptEvent(number,state,message,duration);
            long started=System.nanoTime();scoped.event("RUNNING",attempt==0?"Node execution started.":"Retry attempt "+(attempt+1)+" started.",0);
            CountDownLatch stopped=new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger phase=new java.util.concurrent.atomic.AtomicInteger();
            Future<T> task=calls.submit(()->{if(!phase.compareAndSet(0,1))return null;try{return operation.apply(number).call();}finally{phase.set(2);stopped.countDown();listener.attemptEvent(number,"TERMINATED","Attempt task terminated.",elapsed(started));}});
            try{
                T output=task.get(policy.timeoutMillis(),TimeUnit.MILLISECONDS);
                scoped.event("SUCCEEDED","Node execution completed on attempt "+(attempt+1)+".",elapsed(started));return output;
            }catch(TimeoutException timeout){
                scoped.event("TIMEOUT","Attempt deadline exceeded.",elapsed(started));cancelTask(task,phase,stopped,scoped,started);boolean terminated=stopped.await(1,TimeUnit.SECONDS);String message="Node timed out after "+policy.timeoutMillis()+" ms.";
                if(!terminated){message+=" The plugin did not stop after interruption; retry was suppressed.";scoped.event("FAILED",message,elapsed(started));throw new IllegalStateException(message,timeout);}
                if(attempt<policy.maxRetries()){scoped.event("RETRYING",message+" Retrying.",elapsed(started));backoff(policy,attempt);}
                else{scoped.event("FAILED",message,elapsed(started));throw new IllegalStateException(message,timeout);}
            }catch(ExecutionException execution){
                Throwable cause=execution.getCause()==null?execution:execution.getCause();String message=safeMessage(cause);
                scoped.event("ATTEMPT_FAILED",message,elapsed(started));
                if(attempt<policy.maxRetries()){scoped.event("RETRYING",message+" Retrying after attempt "+(attempt+1)+".",elapsed(started));backoff(policy,attempt);}
                else{scoped.event("FAILED",message,elapsed(started));if(cause instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(message,cause);}
            }catch(CancellationException cancellation){throw cancellation;}
            catch(InterruptedException interrupted){scoped.event("CANCELLATION_REQUESTED","Attempt interruption requested.",elapsed(started));cancelTask(task,phase,stopped,scoped,started);throw interrupted;}
        }
    }
    private static void cancelTask(Future<?> task,java.util.concurrent.atomic.AtomicInteger phase,CountDownLatch stopped,Listener listener,long started){task.cancel(true);if(phase.compareAndSet(0,2)){stopped.countDown();listener.event("TERMINATED","Attempt cancelled before task entry.",elapsed(started));}}

    private static void backoff(NodeExecutionPolicy policy,int attempt)throws InterruptedException{
        if(policy.initialBackoffMillis()==0)return;
        long multiplier=1L<<Math.min(attempt,30);long delay;
        try{delay=Math.multiplyExact(policy.initialBackoffMillis(),multiplier);}catch(ArithmeticException overflow){delay=Long.MAX_VALUE;}
        Thread.sleep(Math.min(delay,policy.maxBackoffMillis()));
    }
    private static long elapsed(long started){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);}
    private static String safeMessage(Throwable error){String value=error.getMessage();if(value==null||value.isBlank())return error.getClass().getSimpleName();return value.length()>500?value.substring(0,500):value;}
    @Override @PreDestroy public void close(){calls.shutdownNow();try{calls.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
