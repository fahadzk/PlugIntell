package com.core.docintel.pipeline.api;
import java.util.Map;
import java.util.Objects;
/** Immutable composition; pass the same attempt reporter explicitly to background tasks. */
public abstract class AbstractPlugin implements Plugin {
    private final PluginMessages messages;
    protected AbstractPlugin(){this(PluginMessages.noop());}
    protected AbstractPlugin(PluginMessages messages){this.messages=Objects.requireNonNull(messages);}
    protected final void emit(String code,Map<String,Object> payload){messages.emit(code,payload);}
    protected final void progress(long completed,long total,String unit){messages.progress(completed,total,unit);}
    protected final void progress(long completed,Long total,String unit){messages.progress(completed,total,unit);}
    protected final void info(String message){messages.info(message);}
    protected final void warning(String message){messages.warning(message);}
}

