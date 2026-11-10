package com.core.docintel.pipeline.events;
public final class UIMessageAdapter implements EventAdapter {
 private final WorkflowStateStore state;
 public UIMessageAdapter(PipelineDescriptor descriptor,WorkflowStateStore state){this.state=state;}
 @Override public void accept(ExecutionEvent event){state.accept(event);}
}

