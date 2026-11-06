package com.core.docintel.pipeline.builder;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.util.concurrent.*;

/** Executes one plugin operation with bounded retries, interruptible backoff, and a hard wait deadline. */
@Singleton
public final class NodeTaskRunner implements AutoCloseable {
    @FunctionalInterface public interface Listener { void event(String state,String message,long durationMillis); }
    private final ExecutorService calls=Executors.newVirtualThreadPerTaskExecutor();

    public <T>T execute(NodeExecutionPolicy policy,Callable<T> operation,Listener listener)throws InterruptedException{
        for(int attempt=0;;attempt++){
            long started=System.nanoTime();listener.event("RUNNING",attempt==0?"Node execution started.":"Retry attempt "+(attempt+1)+" started.",0);
            CountDownLatch stopped=new CountDownLatch(1);
            Future<T> task=calls.submit(()->{try{return operation.call();}finally{stopped.countDown();}});
            try{
                T output=task.get(policy.timeoutMillis(),TimeUnit.MILLISECONDS);
                listener.event("SUCCEEDED","Node execution completed on attempt "+(attempt+1)+".",elapsed(started));return output;
            }catch(TimeoutException timeout){
                task.cancel(true);boolean terminated=stopped.await(1,TimeUnit.SECONDS);String message="Node timed out after "+policy.timeoutMillis()+" ms.";
                if(!terminated){message+=" The plugin did not stop after interruption; retry was suppressed.";listener.event("FAILED",message,elapsed(started));throw new IllegalStateException(message,timeout);}
                if(attempt<policy.maxRetries()){listener.event("RETRYING",message+" Retrying.",elapsed(started));backoff(policy,attempt);}
                else{listener.event("FAILED",message,elapsed(started));throw new IllegalStateException(message,timeout);}
            }catch(ExecutionException execution){
                Throwable cause=execution.getCause()==null?execution:execution.getCause();String message=safeMessage(cause);
                if(attempt<policy.maxRetries()){listener.event("RETRYING",message+" Retrying after attempt "+(attempt+1)+".",elapsed(started));backoff(policy,attempt);}
                else{listener.event("FAILED",message,elapsed(started));if(cause instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException(message,cause);}
            }catch(CancellationException cancellation){throw cancellation;}
            catch(InterruptedException interrupted){task.cancel(true);throw interrupted;}
        }
    }

    private static void backoff(NodeExecutionPolicy policy,int attempt)throws InterruptedException{
        if(policy.initialBackoffMillis()==0)return;
        long multiplier=1L<<Math.min(attempt,30);long delay;
        try{delay=Math.multiplyExact(policy.initialBackoffMillis(),multiplier);}catch(ArithmeticException overflow){delay=Long.MAX_VALUE;}
        Thread.sleep(Math.min(delay,policy.maxBackoffMillis()));
    }
    private static long elapsed(long started){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);}
    private static String safeMessage(Throwable error){String value=error.getMessage();if(value==null||value.isBlank())return error.getClass().getSimpleName();return value.length()>500?value.substring(0,500):value;}
    @Override @PreDestroy public void close(){calls.shutdownNow();}
}
