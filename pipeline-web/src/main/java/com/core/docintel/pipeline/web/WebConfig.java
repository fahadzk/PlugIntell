package com.core.docintel.pipeline.web;
import java.nio.file.*;
public record WebConfig(int port,boolean openBrowser,Path inputRoot){
 public WebConfig{if(port<0||port>65535)throw new IllegalArgumentException("Invalid web port.");inputRoot=inputRoot.toAbsolutePath().normalize();}
 public Path ingestionFile(String input){try{Files.createDirectories(inputRoot);Path root=inputRoot.toRealPath();Path given=Path.of(input),real=(given.isAbsolute()?given:root.resolve(given)).toRealPath();if(!real.startsWith(root)||!Files.isRegularFile(real)||!Files.isReadable(real))throw new IllegalArgumentException("Input must be a readable file inside PIPELINE_INPUT_ROOT.");return real;}catch(java.io.IOException e){throw new IllegalArgumentException("Input must be a readable file inside PIPELINE_INPUT_ROOT.");}}
}

