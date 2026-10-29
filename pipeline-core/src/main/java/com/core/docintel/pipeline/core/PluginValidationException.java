package com.core.docintel.pipeline.core;

public class PluginValidationException extends RuntimeException {
    public PluginValidationException(String message) { super(message); }
    public PluginValidationException(String message, Throwable cause) { super(message, cause); }
}
