package com.core.docintel.pipeline.logging;
import org.slf4j.*;
import org.slf4j.helpers.*;
import org.slf4j.spi.*;
import java.util.concurrent.ConcurrentHashMap;

/** SLF4J 2 backend using the JDK logging configuration and handlers. */
public final class JulLoggingProvider implements SLF4JServiceProvider {
    private final ConcurrentHashMap<String,Logger> loggers=new ConcurrentHashMap<>();
    private final IMarkerFactory markers=new BasicMarkerFactory();
    private final MDCAdapter mdc=new BasicMDCAdapter();
    public ILoggerFactory getLoggerFactory(){return name->loggers.computeIfAbsent(name,JulLogger::new);}
    public IMarkerFactory getMarkerFactory(){return markers;}
    public MDCAdapter getMDCAdapter(){return mdc;}
    public String getRequestedApiVersion(){return "2.0.99";}
    public void initialize(){}
    private static final class JulLogger extends AbstractLogger {
        private final java.util.logging.Logger delegate;
        JulLogger(String name){this.name=name;delegate=java.util.logging.Logger.getLogger(name);}
        protected String getFullyQualifiedCallerName(){return JulLogger.class.getName();}
        private static java.util.logging.Level level(org.slf4j.event.Level level){return switch(level){
            case TRACE->java.util.logging.Level.FINEST;case DEBUG->java.util.logging.Level.FINE;
            case INFO->java.util.logging.Level.INFO;case WARN->java.util.logging.Level.WARNING;case ERROR->java.util.logging.Level.SEVERE;};}
        protected void handleNormalizedLoggingCall(org.slf4j.event.Level level,Marker marker,String message,Object[] arguments,Throwable error){
            delegate.log(level(level),MessageFormatter.arrayFormat(message,arguments).getMessage(),error);
        }
        public boolean isTraceEnabled(){return delegate.isLoggable(java.util.logging.Level.FINEST);}
        public boolean isDebugEnabled(){return delegate.isLoggable(java.util.logging.Level.FINE);}
        public boolean isInfoEnabled(){return delegate.isLoggable(java.util.logging.Level.INFO);}
        public boolean isWarnEnabled(){return delegate.isLoggable(java.util.logging.Level.WARNING);}
        public boolean isErrorEnabled(){return delegate.isLoggable(java.util.logging.Level.SEVERE);}
        public boolean isTraceEnabled(Marker marker){return isTraceEnabled();}
        public boolean isDebugEnabled(Marker marker){return isDebugEnabled();}
        public boolean isInfoEnabled(Marker marker){return isInfoEnabled();}
        public boolean isWarnEnabled(Marker marker){return isWarnEnabled();}
        public boolean isErrorEnabled(Marker marker){return isErrorEnabled();}
    }
}
