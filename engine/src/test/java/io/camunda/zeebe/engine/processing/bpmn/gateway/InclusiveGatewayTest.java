/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.bpmn.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.camunda.zeebe.engine.util.EngineRule;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.ServiceTask;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.intent.IncidentIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.camunda.zeebe.test.util.Strings;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public final class InclusiveGatewayTest {

  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();
  private static final String PROCESS_ID = "process";
  private static final BpmnModelInstance INCLUSIVE_PROCESS =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent("start")
          .inclusiveGateway("inclusive")
          .sequenceFlowId("s1")
          .conditionExpression("= contains(str,\"a\")")
          .serviceTask("task1", b -> b.zeebeJobType("type1"))
          .endEvent("end1")
          .moveToNode("inclusive")
          .sequenceFlowId("s2")
          .conditionExpression("= contains(str,\"b\")")
          .serviceTask("task2", b -> b.zeebeJobType("type2"))
          .endEvent("end2")
          .moveToLastInclusiveGateway()
          .defaultFlow()
          .sequenceFlowId("s3")
          .conditionExpression("= contains(str,\"c\")")
          .serviceTask("task3", b -> b.zeebeJobType("type3"))
          .endEvent("end3")
          .done();
  private static final BpmnModelInstance INCLUSIVE_JOIN_PROCESS =
      Bpmn.createExecutableProcess(PROCESS_ID)
          .startEvent("start")
          .inclusiveGateway("fork")
          .defaultFlow()
          .sequenceFlowId("flow1")
          .conditionExpression("= contains(str,\"a\")")
          .serviceTask("task1", b -> b.zeebeJobType("type1"))
          .inclusiveGateway("join")
          .endEvent("end")
          .moveToNode("fork")
          .sequenceFlowId("flow2")
          .conditionExpression("= contains(str,\"b\")")
          .serviceTask("task2", b -> b.zeebeJobType("type2"))
          .connectTo("join")
          .moveToNode("fork")
          .sequenceFlowId("flow3")
          .conditionExpression("= contains(str,\"c\")")
          .serviceTask("task3", b -> b.zeebeJobType("type3"))
          .connectTo("join")
          .done();

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  @Test
  public void shouldSplitOnInclusiveGateway() {
    // given
    final String processId = Strings.newRandomValidBpmnId();
    final BpmnModelInstance processDefinition =
        Bpmn.createExecutableProcess(processId)
            .startEvent()
            .inclusiveGateway("inclusive")
            .sequenceFlowId("s1")
            .conditionExpression("= contains(str,\"a\")")
            .endEvent("end1")
            .moveToLastGateway()
            .sequenceFlowId("s2")
            .conditionExpression("= contains(str,\"b\")")
            .endEvent("end2")
            .moveToLastInclusiveGateway()
            .defaultFlow()
            .sequenceFlowId("s3")
            .conditionExpression("= contains(str,\"c\")")
            .endEvent("end3")
            .done();
    ENGINE.deployment().withXmlResource(processDefinition).deploy();

    // when
    final long processInstance1 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a").create();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .withProcessInstanceKey(processInstance1)
                .limitToProcessInstanceCompleted()
                .withElementType(BpmnElementType.END_EVENT)
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED))
        .describedAs("when s1's condition is true,then s1 sequence flow is taken")
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getProcessInstanceKey(), v.getElementId()))
        .contains(tuple(processInstance1, "end1"));

    // when
    final long processInstance2 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "b").create();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .withProcessInstanceKey(processInstance2)
                .limitToProcessInstanceCompleted()
                .withElementType(BpmnElementType.END_EVENT)
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED))
        .describedAs("when s2's condition is true,then s2 sequence flow is taken")
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getProcessInstanceKey(), v.getElementId()))
        .contains(tuple(processInstance2, "end2"));

    // when
    final long processInstance3 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "c").create();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .withProcessInstanceKey(processInstance3)
                .limitToProcessInstanceCompleted()
                .withElementType(BpmnElementType.END_EVENT)
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED))
        .describedAs("when s3's condition is true,then s3 sequence flow is taken")
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getProcessInstanceKey(), v.getElementId()))
        .contains(tuple(processInstance3, "end3"));

    // when
    final long processInstance4 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "d").create();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .withProcessInstanceKey(processInstance4)
                .limitToProcessInstanceCompleted()
                .withElementType(BpmnElementType.END_EVENT)
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED))
        .describedAs("when none condition is true,then the default sequence flow is taken")
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getProcessInstanceKey(), v.getElementId()))
        .contains(tuple(processInstance4, "end3"));

    // when
    final long processInstance5 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a,b").create();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstance5)
                .limitToProcessInstanceCompleted()
                .withElementType(BpmnElementType.END_EVENT)
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED))
        .extracting(Record::getValue)
        .describedAs(
            "when s1 and s2's conditions are true,then s1 and s2's sequence flows are taken")
        .extracting(v -> tuple(v.getProcessInstanceKey(), v.getElementId()))
        .contains(tuple(processInstance5, "end1"), tuple(processInstance5, "end2"));

    // when
    final long processInstance6 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "b,c").create();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .withProcessInstanceKey(processInstance6)
                .limitToProcessInstanceCompleted()
                .withElementType(BpmnElementType.END_EVENT)
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED))
        .describedAs(
            "when s2 and s3's conditions are true,then s2 and s3's sequence flows are taken")
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getProcessInstanceKey(), v.getElementId()))
        .contains(tuple(processInstance6, "end2"), tuple(processInstance6, "end3"));

    // when
    final long processInstance7 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a,c").create();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .withProcessInstanceKey(processInstance7)
                .limitToProcessInstanceCompleted()
                .withElementType(BpmnElementType.END_EVENT)
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED))
        .describedAs(
            "when s1 and s3's conditions are true,then s1 and s3's sequence flows are taken")
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getProcessInstanceKey(), v.getElementId()))
        .contains(tuple(processInstance7, "end1"), tuple(processInstance7, "end3"));

    // when
    final long processInstance8 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a,b,c").create();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
                .withProcessInstanceKey(processInstance8)
                .limitToProcessInstanceCompleted()
                .withElementType(BpmnElementType.END_EVENT)
                .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED))
        .describedAs("when all conditions are true,then all sequence flows are taken")
        .extracting(Record::getValue)
        .extracting(v -> tuple(v.getProcessInstanceKey(), v.getElementId()))
        .contains(
            tuple(processInstance8, "end1"),
            tuple(processInstance8, "end2"),
            tuple(processInstance8, "end3"));
  }

  @Test
  public void shouldTakeTheRightSequence() {
    // given
    final String processId = Strings.newRandomValidBpmnId();
    final BpmnModelInstance processDefinition =
        Bpmn.createExecutableProcess(processId)
            .startEvent()
            .inclusiveGateway("split")
            .sequenceFlowId("s1")
            .conditionExpression("= contains(str,\"a\")")
            .inclusiveGateway("joinRequest")
            .moveToLastInclusiveGateway()
            .defaultFlow()
            .sequenceFlowId("s2")
            .conditionExpression("= contains(str,\"b\")")
            .connectTo("joinRequest")
            .endEvent("end")
            .done();

    ENGINE.deployment().withXmlResource(processDefinition).deploy();

    // when
    final long processInstance1 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a,b").create();

    // then
    List<String> takenSequenceFlows =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance1)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN)
            .map(s -> s.getValue().getElementId())
            .collect(Collectors.toList());
    assertThat(takenSequenceFlows)
        .describedAs("when all conditions are true,then all sequence flows is taken")
        .contains("s1")
        .contains("s2");

    // when
    final long processInstance2 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a").create();

    // then
    takenSequenceFlows =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance2)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN)
            .map(s -> s.getValue().getElementId())
            .collect(Collectors.toList());
    assertThat(takenSequenceFlows)
        .describedAs("when the s1's is true,then s1's sequence flow is taken")
        .contains("s1")
        .doesNotContain("s2");

    // when
    final long processInstance3 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "b,c").create();

    // then
    takenSequenceFlows =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance3)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN)
            .map(s -> s.getValue().getElementId())
            .collect(Collectors.toList());
    assertThat(takenSequenceFlows)
        .describedAs("when the s2's is true,then s2's sequence flow is taken")
        .contains("s2")
        .doesNotContain("s1");

    // when
    final long processInstance4 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "d").create();

    // then
    takenSequenceFlows =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance4)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN)
            .map(s -> s.getValue().getElementId())
            .collect(Collectors.toList());
    assertThat(takenSequenceFlows)
        .describedAs("when none condition is true,then the default s2's sequence flow is taken")
        .contains("s2")
        .doesNotContain("s1");
  }

  @Test
  public void shouldSetSourceRecordPositionCorrectOnJoinInclusive() {
    // given
    final String processId = Strings.newRandomValidBpmnId();
    final BpmnModelInstance processDefinition =
        Bpmn.createExecutableProcess(processId)
            .startEvent()
            .inclusiveGateway("inclusive")
            .sequenceFlowId("s1")
            .conditionExpression("= contains(str,\"a\")")
            .inclusiveGateway("joinRequest")
            .moveToLastInclusiveGateway()
            .defaultFlow()
            .sequenceFlowId("s2")
            .conditionExpression("= contains(str,\"b\")")
            .connectTo("joinRequest")
            .endEvent("end")
            .done();

    ENGINE.deployment().withXmlResource(processDefinition).deploy();

    // when
    final long processInstance1 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a,b").create();
    // then
    List<Record<ProcessInstanceRecordValue>> sequenceFlows =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance1)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN)
            .asList();

    List<Record<ProcessInstanceRecordValue>> gateWays =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance1)
            .limitToProcessInstanceCompleted()
            .withElementId("joinRequest")
            .asList();

    // assert that gateway activation originates from the same command as the sequence flow taken
    // and that the correct flow was taken
    assertThat(gateWays.get(0).getSourceRecordPosition())
        .isEqualTo(sequenceFlows.get(1).getSourceRecordPosition());
    assertThat(gateWays.get(0).getSourceRecordPosition())
        .isEqualTo(sequenceFlows.get(2).getSourceRecordPosition());
    assertThat(sequenceFlows.get(1).getValue().getElementId()).isEqualTo("s2");
    assertThat(sequenceFlows.get(2).getValue().getElementId()).isEqualTo("s1");

    // when
    final long processInstance2 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "b").create();
    // then
    sequenceFlows =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance2)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN)
            .asList();

    gateWays =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance2)
            .limitToProcessInstanceCompleted()
            .withElementId("joinRequest")
            .asList();

    assertThat(gateWays.get(0).getSourceRecordPosition())
        .isEqualTo(sequenceFlows.get(1).getSourceRecordPosition());
    assertThat(sequenceFlows.get(1).getValue().getElementId()).isEqualTo("s2");

    // when
    final long processInstance3 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "d").create();
    // then
    sequenceFlows =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance3)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN)
            .asList();

    gateWays =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance3)
            .limitToProcessInstanceCompleted()
            .withElementId("joinRequest")
            .asList();

    assertThat(gateWays.get(0).getSourceRecordPosition())
        .isEqualTo(sequenceFlows.get(1).getSourceRecordPosition());
    assertThat(sequenceFlows.get(1).getValue().getElementId()).isEqualTo("s2");

    // when
    final long processInstance4 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a").create();
    // then
    sequenceFlows =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance4)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN)
            .asList();

    gateWays =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstance4)
            .limitToProcessInstanceCompleted()
            .withElementId("joinRequest")
            .asList();

    assertThat(gateWays.get(0).getSourceRecordPosition())
        .isEqualTo(sequenceFlows.get(1).getSourceRecordPosition());
    assertThat(sequenceFlows.get(1).getValue().getElementId()).isEqualTo("s1");
  }

  @Test
  public void testProcessInstanceStatesWithInclusiveGateway() {
    // given
    final String processId = Strings.newRandomValidBpmnId();
    final BpmnModelInstance processDefinition =
        Bpmn.createExecutableProcess(processId)
            .startEvent()
            .inclusiveGateway("inclusive")
            .sequenceFlowId("s1")
            .conditionExpression("= contains(str,\"a\")")
            .endEvent("a")
            .moveToLastInclusiveGateway()
            .defaultFlow()
            .sequenceFlowId("s2")
            .conditionExpression("= contains(str,\"b\")")
            .endEvent("b")
            .done();

    ENGINE.deployment().withXmlResource(processDefinition).deploy();

    // when
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a").create();

    // then
    List<Record<ProcessInstanceRecordValue>> processEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .skipUntil(r -> r.getValue().getElementId().equals("inclusive"))
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(processEvents)
        .extracting(Record::getIntent)
        .containsExactly(
            ProcessInstanceIntent.ACTIVATE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED,
            ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN,
            ProcessInstanceIntent.ACTIVATE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED,
            ProcessInstanceIntent.COMPLETE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED);

    // when
    final long processInstanceKey2 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "b").create();

    // then
    processEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey2)
            .skipUntil(r -> r.getValue().getElementId().equals("inclusive"))
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(processEvents)
        .extracting(Record::getIntent)
        .containsExactly(
            ProcessInstanceIntent.ACTIVATE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED,
            ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN,
            ProcessInstanceIntent.ACTIVATE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED,
            ProcessInstanceIntent.COMPLETE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED);

    // when
    final long processInstanceKey3 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "c").create();

    // then
    processEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey3)
            .skipUntil(r -> r.getValue().getElementId().equals("inclusive"))
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(processEvents)
        .extracting(Record::getIntent)
        .containsExactly(
            ProcessInstanceIntent.ACTIVATE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED,
            ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN,
            ProcessInstanceIntent.ACTIVATE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED,
            ProcessInstanceIntent.COMPLETE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED);

    // when
    final long processInstanceKey4 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a,b").create();

    // then
    processEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey4)
            .skipUntil(r -> r.getValue().getElementId().equals("inclusive"))
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(processEvents)
        .extracting(Record::getIntent)
        .containsExactly(
            ProcessInstanceIntent.ACTIVATE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED,
            ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN,
            ProcessInstanceIntent.ACTIVATE_ELEMENT,
            ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN,
            ProcessInstanceIntent.ACTIVATE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED,
            ProcessInstanceIntent.ELEMENT_ACTIVATING,
            ProcessInstanceIntent.ELEMENT_ACTIVATED,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED,
            ProcessInstanceIntent.COMPLETE_ELEMENT,
            ProcessInstanceIntent.ELEMENT_COMPLETING,
            ProcessInstanceIntent.ELEMENT_COMPLETED);
  }

  @Test
  public void shouldResolveIncidentsWhenTerminating() {
    // given
    final String processId = Strings.newRandomValidBpmnId();
    ENGINE
        .deployment()
        .withXmlResource(
            Bpmn.createExecutableProcess(processId)
                .startEvent()
                .inclusiveGateway("inclusive")
                .sequenceFlowId("s1")
                .defaultFlow()
                .endEvent("default-end")
                .moveToLastGateway()
                .sequenceFlowId("s2")
                .conditionExpression("nonexisting_variable")
                .endEvent("non-default-end")
                .done())
        .deploy();
    final long processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a,b").create();
    assertThat(
            RecordingExporter.incidentRecords().withProcessInstanceKey(processInstanceKey).limit(1))
        .extracting(Record::getIntent)
        .containsExactly(IncidentIntent.CREATED);

    // when
    ENGINE.processInstance().withInstanceKey(processInstanceKey).cancel();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceTerminated())
        .extracting(r -> tuple(r.getValue().getBpmnElementType(), r.getIntent()))
        .containsSubsequence(
            tuple(BpmnElementType.PROCESS, ProcessInstanceIntent.ELEMENT_TERMINATING),
            tuple(BpmnElementType.INCLUSIVE_GATEWAY, ProcessInstanceIntent.ELEMENT_TERMINATING),
            tuple(BpmnElementType.INCLUSIVE_GATEWAY, ProcessInstanceIntent.ELEMENT_TERMINATED),
            tuple(BpmnElementType.PROCESS, ProcessInstanceIntent.ELEMENT_TERMINATED));

    assertThat(
            RecordingExporter.incidentRecords().withProcessInstanceKey(processInstanceKey).limit(2))
        .extracting(Record::getIntent)
        .containsExactly(IncidentIntent.CREATED, IncidentIntent.RESOLVED);
  }

  @Test
  public void shouldActivateTasksOnInclusiveBranches() {
    // given
    ENGINE.deployment().withXmlResource(INCLUSIVE_PROCESS).deploy();

    // when
    final long processInstanceKey1 =
        ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).withVariable("str", "a,b,c").create();

    // then
    List<Record<ProcessInstanceRecordValue>> taskEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey1)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.ELEMENT_ACTIVATED)
            .filter(e -> isServiceTaskInProcess(e.getValue().getElementId(), INCLUSIVE_PROCESS))
            .asList();

    assertThat(taskEvents).hasSize(3);
    assertThat(taskEvents)
        .extracting(e -> e.getValue().getElementId())
        .containsExactlyInAnyOrder("task1", "task2", "task3");
    assertThat(taskEvents.get(0).getKey()).isNotEqualTo(taskEvents.get(1).getKey());
    assertThat(taskEvents.get(0).getKey()).isNotEqualTo(taskEvents.get(2).getKey());
    assertThat(taskEvents.get(1).getKey()).isNotEqualTo(taskEvents.get(2).getKey());

    // when
    final long processInstanceKey2 =
        ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).withVariable("str", "a,c").create();

    // then
    taskEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey2)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.ELEMENT_ACTIVATED)
            .filter(e -> isServiceTaskInProcess(e.getValue().getElementId(), INCLUSIVE_PROCESS))
            .asList();

    assertThat(taskEvents).hasSize(2);
    assertThat(taskEvents)
        .extracting(e -> e.getValue().getElementId())
        .containsExactlyInAnyOrder("task1", "task3");
    assertThat(taskEvents.get(0).getKey()).isNotEqualTo(taskEvents.get(1).getKey());
    // when
    final long processInstanceKey3 =
        ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).withVariable("str", "d").create();

    // then
    taskEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey3)
            .limitToProcessInstanceCompleted()
            .withIntent(ProcessInstanceIntent.ELEMENT_ACTIVATED)
            .filter(e -> isServiceTaskInProcess(e.getValue().getElementId(), INCLUSIVE_PROCESS))
            .asList();

    assertThat(taskEvents).hasSize(1);
    assertThat(taskEvents).extracting(e -> e.getValue().getElementId()).containsExactly("task3");
  }

  @Test
  public void shouldCompleteScopeWhenAllPathsCompleted() {
    // given
    ENGINE.deployment().withXmlResource(INCLUSIVE_PROCESS).deploy();
    final long processInstanceKey1 =
        ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).withVariable("str", "a,b,c").create();
    ENGINE.job().ofInstance(processInstanceKey1).withType("type1").complete();

    // when
    ENGINE.job().ofInstance(processInstanceKey1).withType("type2").complete();
    ENGINE.job().ofInstance(processInstanceKey1).withType("type3").complete();

    // then
    List<Record<ProcessInstanceRecordValue>> completedEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey1)
            .limitToProcessInstanceCompleted()
            .withElementType(BpmnElementType.END_EVENT)
            .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
            .asList();

    assertThat(completedEvents)
        .extracting(e -> e.getValue().getElementId())
        .containsExactly("end1", "end2", "end3");

    RecordingExporter.processInstanceRecords()
        .withElementId(PROCESS_ID)
        .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
        .getFirst();

    // when
    final long processInstanceKey2 =
        ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).withVariable("str", "a,b").create();
    ENGINE.job().ofInstance(processInstanceKey2).withType("type1").complete();

    ENGINE.job().ofInstance(processInstanceKey2).withType("type2").complete();

    // then
    completedEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey2)
            .limitToProcessInstanceCompleted()
            .withElementType(BpmnElementType.END_EVENT)
            .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
            .asList();

    assertThat(completedEvents)
        .extracting(e -> e.getValue().getElementId())
        .containsExactly("end1", "end2");

    RecordingExporter.processInstanceRecords()
        .withElementId(PROCESS_ID)
        .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
        .getFirst();

    // when
    final long processInstanceKey3 =
        ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).withVariable("str", "d").create();

    ENGINE.job().ofInstance(processInstanceKey3).withType("type3").complete();

    // then
    completedEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey3)
            .limitToProcessInstanceCompleted()
            .withElementType(BpmnElementType.END_EVENT)
            .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
            .asList();

    assertThat(completedEvents)
        .extracting(e -> e.getValue().getElementId())
        .containsExactly("end3");

    RecordingExporter.processInstanceRecords()
        .withElementId(PROCESS_ID)
        .withIntent(ProcessInstanceIntent.ELEMENT_COMPLETED)
        .getFirst();
  }

  @Test
  public void shouldCompleteScopeWithMultipleTokensOnSamePath() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent()
            .inclusiveGateway("inclusive")
            .exclusiveGateway("join")
            .endEvent("end")
            .moveToNode("inclusive")
            .connectTo("join")
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();

    // when
    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();

    // then
    final List<Record<ProcessInstanceRecordValue>> processInstanceEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .limitToProcessInstanceCompleted()
            .collect(Collectors.toList());

    assertThat(processInstanceEvents)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("end", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(PROCESS_ID, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldPassThroughInclusiveGateway() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent("start")
            .sequenceFlowId("flow1")
            .inclusiveGateway("inclusive")
            .sequenceFlowId("flow2")
            .endEvent("end")
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();

    // when
    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();

    // then
    final List<Record<ProcessInstanceRecordValue>> processInstanceEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .limitToProcessInstanceCompleted()
            .collect(Collectors.toList());

    assertThat(processInstanceEvents)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSequence(
            tuple("inclusive", ProcessInstanceIntent.ELEMENT_ACTIVATING),
            tuple("inclusive", ProcessInstanceIntent.ELEMENT_ACTIVATED),
            tuple("inclusive", ProcessInstanceIntent.ELEMENT_COMPLETING),
            tuple("inclusive", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("flow2", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("end", ProcessInstanceIntent.ACTIVATE_ELEMENT),
            tuple("end", ProcessInstanceIntent.ELEMENT_ACTIVATING),
            tuple("end", ProcessInstanceIntent.ELEMENT_ACTIVATED),
            tuple("end", ProcessInstanceIntent.ELEMENT_COMPLETING),
            tuple("end", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(PROCESS_ID, ProcessInstanceIntent.COMPLETE_ELEMENT),
            tuple(PROCESS_ID, ProcessInstanceIntent.ELEMENT_COMPLETING),
            tuple(PROCESS_ID, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldCompleteScopeOnInclusiveGateway() {
    // given
    final String processId = Strings.newRandomValidBpmnId();
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(processId)
            .startEvent("start")
            .sequenceFlowId("flow1")
            .inclusiveGateway("inclusive")
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();

    // when
    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(processId).create();

    // then
    final List<Record<ProcessInstanceRecordValue>> processInstanceEvents =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(processInstanceEvents)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSequence(
            tuple("inclusive", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.COMPLETE_ELEMENT));
  }

  @Test
  public void shouldMergeInclusiveBranchesWithServiceTasks() {
    // given
    ENGINE.deployment().withXmlResource(INCLUSIVE_JOIN_PROCESS).deploy();

    // when
    final long processInstanceKey1 =
        ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).withVariable("str", "a,b,c").create();
    ENGINE.job().ofInstance(processInstanceKey1).withType("type1").complete();

    // then
    ENGINE.job().ofInstance(processInstanceKey1).withType("type2").complete();
    ENGINE.job().ofInstance(processInstanceKey1).withType("type3").complete();
    final List<Record<ProcessInstanceRecordValue>> events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey1)
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(events)
        .describedAs(
            "after all sequence flows are taken, the joining inclusive gateway is activated exactly once")
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("flow1", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsSubsequence(
            tuple("flow2", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsSubsequence(
            tuple("flow3", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));
  }

  @Test
  public void shouldOnlyTriggerGatewayWhenAllBranchesAreActivated() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent()
            .inclusiveGateway("fork")
            .exclusiveGateway("exclusiveJoin")
            .moveToLastGateway()
            .connectTo("exclusiveJoin")
            .sequenceFlowId("joinFlow1")
            .inclusiveGateway("join")
            .moveToNode("fork")
            .serviceTask("waitState", b -> b.zeebeJobType("type"))
            .sequenceFlowId("joinFlow2")
            .connectTo("join")
            .endEvent()
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();

    // waiting until we have signalled the first incoming sequence flow twice
    // => this should not trigger the gateway yet
    RecordingExporter.processInstanceRecords()
        .limit(r -> "joinFlow1".equals(r.getValue().getElementId()))
        .limit(2)
        .skip(1)
        .getFirst();

    // when
    // we complete the job
    ENGINE.job().ofInstance(processInstanceKey).withType("type").complete();

    // then
    final List<Record<ProcessInstanceRecordValue>> events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(events)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("joinFlow1", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("joinFlow1", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("joinFlow2", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));
  }

  @Test
  public void shouldMergeAndSplitInOneGateway() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess(PROCESS_ID)
            .startEvent("start")
            .inclusiveGateway("fork")
            .sequenceFlowId("s1")
            .inclusiveGateway("join")
            .moveToNode("fork")
            .sequenceFlowId("s2")
            .connectTo("join")
            .sequenceFlowId("s3")
            .serviceTask("task1", b -> b.zeebeJobType("type1"))
            .moveToLastGateway()
            .sequenceFlowId("s4")
            .serviceTask("task2", b -> b.zeebeJobType("type2"))
            .done();

    ENGINE.deployment().withXmlResource(process).deploy();

    // when
    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(PROCESS_ID).create();

    ENGINE.job().ofInstance(processInstanceKey).withType("type1").complete();
    ENGINE.job().ofInstance(processInstanceKey).withType("type2").complete();
    // then
    final List<Record<ProcessInstanceRecordValue>> elementInstances =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .limitToProcessInstanceCompleted()
            .filter(
                r ->
                    r.getIntent() == ProcessInstanceIntent.ELEMENT_ACTIVATED
                        && r.getValue().getBpmnElementType() == BpmnElementType.SERVICE_TASK)
            .asList();

    assertThat(elementInstances)
        .extracting(e -> e.getValue().getElementId())
        .containsExactlyInAnyOrder("task1", "task2");
  }

  @Test
  public void shouldMergeInclusiveBranchesWithManualTask() {
    // given
    final String processId = Strings.newRandomValidBpmnId();
    final BpmnModelInstance processDefinition =
        Bpmn.createExecutableProcess(processId)
            .startEvent("start")
            .inclusiveGateway("fork")
            .sequenceFlowId("flow1")
            .inclusiveGateway("join")
            .endEvent("end")
            .moveToNode("fork")
            .sequenceFlowId("flow2")
            .manualTask("m2")
            .connectTo("join")
            .moveToNode("fork")
            .sequenceFlowId("flow3")
            .manualTask("m3")
            .connectTo("join")
            .done();
    // given
    ENGINE.deployment().withXmlResource(processDefinition).deploy();

    // when
    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(processId).create();

    // then
    final List<Record<ProcessInstanceRecordValue>> events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(events)
        .describedAs(
            "when there is no any task,after all sequence flows are taken, the joining inclusive gateway is activated exactly once")
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("flow1", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsSubsequence(
            tuple("flow2", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsSubsequence(
            tuple("flow3", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));
  }

  @Test
  public void shouldMergeInclusiveBranchesWithSomeServiceTask() {
    // given
    final String processId = Strings.newRandomValidBpmnId();
    final BpmnModelInstance processDefinition =
        Bpmn.createExecutableProcess(processId)
            .startEvent("start")
            .inclusiveGateway("fork")
            .defaultFlow()
            .sequenceFlowId("flow1")
            .conditionExpression("= contains(str,\"a\")")
            .serviceTask("task1", b -> b.zeebeJobType("type1"))
            .inclusiveGateway("join")
            .endEvent("end")
            .moveToNode("fork")
            .sequenceFlowId("flow2")
            .conditionExpression("= contains(str,\"b\")")
            .connectTo("join")
            .moveToNode("fork")
            .sequenceFlowId("flow3")
            .conditionExpression("= contains(str,\"c\")")
            .serviceTask("task3", b -> b.zeebeJobType("type3"))
            .connectTo("join")
            .done();
    // given
    ENGINE.deployment().withXmlResource(processDefinition).deploy();

    // when
    final long processInstanceKey1 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a,b,c").create();

    // then
    ENGINE.job().ofInstance(processInstanceKey1).withType("type1").complete();
    ENGINE.job().ofInstance(processInstanceKey1).withType("type3").complete();
    List<Record<ProcessInstanceRecordValue>> events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey1)
            .limitToProcessInstanceCompleted()
            .collect(Collectors.toList());

    assertThat(events)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("flow1", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsSubsequence(
            tuple("flow2", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsSubsequence(
            tuple("flow3", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));

    // when
    final long processInstanceKey2 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a,c").create();

    // then
    ENGINE.job().ofInstance(processInstanceKey2).withType("type1").complete();
    ENGINE.job().ofInstance(processInstanceKey2).withType("type3").complete();

    events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey2)
            .limitToProcessInstanceCompleted()
            .collect(Collectors.toList());

    assertThat(events)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("flow1", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsSubsequence(
            tuple("flow3", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));

    // when
    final long processInstanceKey3 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "b").create();

    events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey3)
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(events)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("flow2", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));

    // when
    final long processInstanceKey4 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "a").create();

    // then
    ENGINE.job().ofInstance(processInstanceKey4).withType("type1").complete();

    events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey4)
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(events)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("flow1", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));

    // when
    final long processInstanceKey5 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "b,c").create();

    // then
    ENGINE.job().ofInstance(processInstanceKey5).withType("type3").complete();

    events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey5)
            .limitToProcessInstanceCompleted()
            .collect(Collectors.toList());

    assertThat(events)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("flow2", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsSubsequence(
            tuple("flow3", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));

    // when
    final long processInstanceKey6 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "c").create();

    // then
    ENGINE.job().ofInstance(processInstanceKey6).withType("type3").complete();

    events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey6)
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(events)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("flow3", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));

    // when
    final long processInstanceKey7 =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("str", "d").create();

    // then
    ENGINE.job().ofInstance(processInstanceKey7).withType("type1").complete();

    events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey7)
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(events)
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("flow1", ProcessInstanceIntent.SEQUENCE_FLOW_TAKEN),
            tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING))
        .containsOnlyOnce(tuple("join", ProcessInstanceIntent.ELEMENT_ACTIVATING));
  }

  @Test
  public void shouldMergeInclusiveBranchesWithParallelGateway() {
    // given
    final String processId = Strings.newRandomValidBpmnId();
    final BpmnModelInstance processDefinition =
        Bpmn.createExecutableProcess(processId)
            .startEvent("start")
            .parallelGateway("parallel-fork")
            .inclusiveGateway("inclusive-fork")
            .manualTask("manual1")
            .inclusiveGateway("inclusive-join")
            .parallelGateway("parallel-join")
            .endEvent("end")
            .moveToNode("parallel-fork")
            .serviceTask("task1", b -> b.zeebeJobType("type1"))
            .connectTo("parallel-join")
            .moveToNode("inclusive-fork")
            .manualTask("manual2")
            .connectTo("inclusive-join")
            .done();
    // given
    ENGINE.deployment().withXmlResource(processDefinition).deploy();

    // when
    final long processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(processId).create();

    // then
    ENGINE.job().ofInstance(processInstanceKey).withType("type1").complete();
    final List<Record<ProcessInstanceRecordValue>> events =
        RecordingExporter.processInstanceRecords()
            .withProcessInstanceKey(processInstanceKey)
            .limitToProcessInstanceCompleted()
            .asList();

    assertThat(events)
        .describedAs(
            "after all sequence flows are taken, the joining inclusive and parallel gateway is activated exactly once")
        .extracting(e -> e.getValue().getElementId(), Record::getIntent)
        .containsOnlyOnce(
            tuple("inclusive-join", ProcessInstanceIntent.ELEMENT_ACTIVATING),
            tuple("parallel-join", ProcessInstanceIntent.ELEMENT_ACTIVATING),
            tuple("end", ProcessInstanceIntent.ELEMENT_ACTIVATING));
  }

  private static boolean isServiceTaskInProcess(
      final String activityId, final BpmnModelInstance process) {
    return process.getModelElementsByType(ServiceTask.class).stream()
        .anyMatch(t -> t.getId().equals(activityId));
  }
}
