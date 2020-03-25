/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow.handlers.servicetask;

import io.zeebe.engine.processor.workflow.BpmnStepContext;
import io.zeebe.engine.processor.workflow.ExpressionProcessor;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableServiceTask;
import io.zeebe.engine.processor.workflow.handlers.element.ElementActivatedHandler;
import io.zeebe.msgpack.value.DocumentValue;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import java.util.Optional;
import org.agrona.DirectBuffer;

public final class ServiceTaskElementActivatedHandler<T extends ExecutableServiceTask>
    extends ElementActivatedHandler<T> {

  private final JobRecord jobCommand = new JobRecord();
  private final ExpressionProcessor expressionProcessor;

  public ServiceTaskElementActivatedHandler(ExpressionProcessor expressionProcessor) {
    this(expressionProcessor, null);
  }

  public ServiceTaskElementActivatedHandler(
      final ExpressionProcessor expressionProcessor, final WorkflowInstanceIntent nextState) {
    super(nextState);
    this.expressionProcessor = expressionProcessor;
  }

  @Override
  protected boolean handleState(final BpmnStepContext<T> context) {
    if (super.handleState(context)) {
      final WorkflowInstanceRecord value = context.getValue();
      final ExecutableServiceTask serviceTask = context.getElement();

      final Optional<DirectBuffer> optJobType =
          expressionProcessor.evaluateStringExpression(serviceTask.getType(), context);

      if (optJobType.isPresent()) {
        populateJobFromTask(context, value, optJobType.get(), serviceTask);
        context.getCommandWriter().appendNewCommand(JobIntent.CREATE, jobCommand);
      }

      return true;
    }

    return false;
  }

  private void populateJobFromTask(
      final BpmnStepContext<T> context,
      final WorkflowInstanceRecord value,
      final DirectBuffer jobType,
      final ExecutableServiceTask serviceTask) {
    final DirectBuffer headers = serviceTask.getEncodedHeaders();

    jobCommand.reset();
    jobCommand
        .setType(jobType)
        .setRetries(serviceTask.getRetries())
        .setVariables(DocumentValue.EMPTY_DOCUMENT)
        .setCustomHeaders(headers)
        .setBpmnProcessId(value.getBpmnProcessIdBuffer())
        .setWorkflowDefinitionVersion(value.getVersion())
        .setWorkflowKey(value.getWorkflowKey())
        .setWorkflowInstanceKey(value.getWorkflowInstanceKey())
        .setElementId(serviceTask.getId())
        .setElementInstanceKey(context.getKey());
  }
}
