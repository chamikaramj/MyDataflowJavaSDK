/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.util;

import static com.google.cloud.dataflow.sdk.util.WindowUtils.windowToString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.google.cloud.dataflow.sdk.coders.BigEndianLongCoder;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.transforms.Combine.CombineFn;
import com.google.cloud.dataflow.sdk.transforms.Combine.KeyedCombineFn;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.FixedWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.IntervalWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.Sessions;
import com.google.cloud.dataflow.sdk.transforms.windowing.SlidingWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.TupleTag;

import org.hamcrest.Matchers;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Unit tests for {@link StreamingGroupAlsoByWindowsDoFn}. */
@RunWith(JUnit4.class)
@SuppressWarnings("rawtypes")
public class StreamingGroupAlsoByWindowsDoFnTest {
  ExecutionContext execContext;
  CounterSet counters;
  TupleTag<KV<String, Iterable<String>>> outputTag;

  @Before public void setUp() {
    execContext = new DirectModeExecutionContext();
    counters = new CounterSet();
    outputTag = new TupleTag<>();
  }

  @Test public void testEmpty() throws Exception {
    DoFnRunner<TimerOrElement<KV<String, String>>, KV<String, Iterable<String>>, List> runner =
        makeRunner(FixedWindows.of(Duration.millis(10)));

    runner.startBundle();

    runner.finishBundle();

    List<?> result = runner.getReceiver(outputTag);

    assertEquals(0, result.size());
  }

  @Test public void testFixedWindows() throws Exception {
    DoFnRunner<TimerOrElement<KV<String, String>>,
        KV<String, Iterable<String>>, List> runner =
        makeRunner(FixedWindows.of(Duration.millis(10)));

    Coder<IntervalWindow> windowCoder = FixedWindows.of(Duration.millis(10)).windowCoder();

    runner.startBundle();

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v1")),
        new Instant(1),
        Arrays.asList(window(0, 10))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v2")),
        new Instant(2),
        Arrays.asList(window(0, 10))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v0")),
        new Instant(0),
        Arrays.asList(window(0, 10))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v3")),
        new Instant(13),
        Arrays.asList(window(10, 20))));

    runner.processElement(WindowedValue.valueInEmptyWindows(
        TimerOrElement.<KV<String, String>>timer(
            windowToString((IntervalWindow) window(0, 10), windowCoder),
            new Instant(9), "k")));

    runner.processElement(WindowedValue.valueInEmptyWindows(
        TimerOrElement.<KV<String, String>>timer(
            windowToString((IntervalWindow) window(10, 20), windowCoder),
            new Instant(19), "k")));

    runner.finishBundle();

    @SuppressWarnings("unchecked")
    List<WindowedValue<KV<String, Iterable<String>>>> result = runner.getReceiver(outputTag);

    assertEquals(2, result.size());

    WindowedValue<KV<String, Iterable<String>>> item0 = result.get(0);
    assertEquals("k", item0.getValue().getKey());
    assertThat(item0.getValue().getValue(), Matchers.containsInAnyOrder("v0", "v1", "v2"));
    assertEquals(new Instant(9), item0.getTimestamp());
    assertThat(item0.getWindows(), Matchers.contains(window(0, 10)));

    WindowedValue<KV<String, Iterable<String>>> item1 = result.get(1);
    assertEquals("k", item1.getValue().getKey());
    assertThat(item1.getValue().getValue(), Matchers.containsInAnyOrder("v3"));
    assertEquals(new Instant(19), item1.getTimestamp());
    assertThat(item1.getWindows(), Matchers.contains(window(10, 20)));
  }

  @Test public void testSlidingWindows() throws Exception {
    DoFnRunner<TimerOrElement<KV<String, String>>,
        KV<String, Iterable<String>>, List> runner =
        makeRunner(SlidingWindows.of(Duration.millis(20)).every(Duration.millis(10)));

    Coder<IntervalWindow> windowCoder =
        SlidingWindows.of(Duration.millis(10)).every(Duration.millis(10)).windowCoder();

    runner.startBundle();

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v1")),
        new Instant(5),
        Arrays.asList(window(-10, 10), window(0, 20))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v0")),
        new Instant(2),
        Arrays.asList(window(-10, 10), window(0, 20))));

    runner.processElement(WindowedValue.valueInEmptyWindows(
        TimerOrElement.<KV<String, String>>timer(
            windowToString((IntervalWindow) window(-10, 10), windowCoder),
            new Instant(9), "k")));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v2")),
        new Instant(5),
        Arrays.asList(window(0, 20), window(10, 30))));

    runner.processElement(WindowedValue.valueInEmptyWindows(
        TimerOrElement.<KV<String, String>>timer(
            windowToString((IntervalWindow) window(0, 20), windowCoder),
            new Instant(19), "k")));

    runner.processElement(WindowedValue.valueInEmptyWindows(
        TimerOrElement.<KV<String, String>>timer(
            windowToString((IntervalWindow) window(10, 30), windowCoder),
            new Instant(29), "k")));

    runner.finishBundle();

    @SuppressWarnings("unchecked")
    List<WindowedValue<KV<String, Iterable<String>>>> result = runner.getReceiver(outputTag);

    assertEquals(3, result.size());

    WindowedValue<KV<String, Iterable<String>>> item0 = result.get(0);
    assertEquals("k", item0.getValue().getKey());
    assertThat(item0.getValue().getValue(), Matchers.containsInAnyOrder("v0", "v1"));
    assertEquals(new Instant(9), item0.getTimestamp());
    assertThat(item0.getWindows(), Matchers.contains(window(-10, 10)));

    WindowedValue<KV<String, Iterable<String>>> item1 = result.get(1);
    assertEquals("k", item1.getValue().getKey());
    assertThat(item1.getValue().getValue(), Matchers.containsInAnyOrder("v0", "v1", "v2"));
    assertEquals(new Instant(19), item1.getTimestamp());
    assertThat(item1.getWindows(), Matchers.contains(window(0, 20)));

    WindowedValue<KV<String, Iterable<String>>> item2 = result.get(2);
    assertEquals("k", item2.getValue().getKey());
    assertThat(item2.getValue().getValue(), Matchers.containsInAnyOrder("v2"));
    assertEquals(new Instant(29), item2.getTimestamp());
    assertThat(item2.getWindows(), Matchers.contains(window(10, 30)));
  }

  @Test public void testSessions() throws Exception {
    DoFnRunner<TimerOrElement<KV<String, String>>,
        KV<String, Iterable<String>>, List> runner =
        makeRunner(Sessions.withGapDuration(Duration.millis(10)));

    Coder<IntervalWindow> windowCoder =
        Sessions.withGapDuration(Duration.millis(10)).windowCoder();

    runner.startBundle();

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v1")),
        new Instant(0),
        Arrays.asList(window(0, 10))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v2")),
        new Instant(5),
        Arrays.asList(window(5, 15))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v3")),
        new Instant(15),
        Arrays.asList(window(15, 25))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", "v0")),
        new Instant(3),
        Arrays.asList(window(3, 13))));

    runner.processElement(WindowedValue.valueInEmptyWindows(
        TimerOrElement.<KV<String, String>>timer(
            windowToString((IntervalWindow) window(0, 15), windowCoder),
            new Instant(14), "k")));

    runner.processElement(WindowedValue.valueInEmptyWindows(
        TimerOrElement.<KV<String, String>>timer(
            windowToString((IntervalWindow) window(15, 25), windowCoder),
            new Instant(24), "k")));

    runner.finishBundle();

    @SuppressWarnings("unchecked")
    List<WindowedValue<KV<String, Iterable<String>>>> result = runner.getReceiver(outputTag);

    assertEquals(2, result.size());

    WindowedValue<KV<String, Iterable<String>>> item0 = result.get(0);
    assertEquals("k", item0.getValue().getKey());
    assertThat(item0.getValue().getValue(), Matchers.containsInAnyOrder("v0", "v1", "v2"));
    assertEquals(new Instant(14), item0.getTimestamp());
    assertThat(item0.getWindows(), Matchers.contains(window(0, 15)));

    WindowedValue<KV<String, Iterable<String>>> item1 = result.get(1);
    assertEquals("k", item1.getValue().getKey());
    assertThat(item1.getValue().getValue(), Matchers.containsInAnyOrder("v3"));
    assertEquals(new Instant(24), item1.getTimestamp());
    assertThat(item1.getWindows(), Matchers.contains(window(15, 25)));
  }

  @Test public void testSessionsCombine() throws Exception {
    CombineFn<Long, ?, Long> combineFn = new Sum.SumLongFn();
    DoFnRunner<TimerOrElement<KV<String, Long>>,
        KV<String, Long>, List> runner =
        makeRunner(Sessions.withGapDuration(Duration.millis(10)),
                   combineFn.<String>asKeyedFn());

    Coder<IntervalWindow> windowCoder =
        Sessions.withGapDuration(Duration.millis(10)).windowCoder();

    runner.startBundle();

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", 1L)),
        new Instant(0),
        Arrays.asList(window(0, 10))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", 2L)),
        new Instant(5),
        Arrays.asList(window(5, 15))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", 3L)),
        new Instant(15),
        Arrays.asList(window(15, 25))));

    runner.processElement(WindowedValue.of(
        TimerOrElement.element(KV.of("k", 4L)),
        new Instant(3),
        Arrays.asList(window(3, 13))));

    runner.processElement(WindowedValue.valueInEmptyWindows(
        TimerOrElement.<KV<String, Long>>timer(
            windowToString((IntervalWindow) window(0, 15), windowCoder),
            new Instant(14), "k")));

    runner.processElement(WindowedValue.valueInEmptyWindows(
        TimerOrElement.<KV<String, Long>>timer(
            windowToString((IntervalWindow) window(15, 25), windowCoder),
            new Instant(24), "k")));

    runner.finishBundle();

    @SuppressWarnings("unchecked")
    List<WindowedValue<KV<String, Long>>> result = runner.getReceiver(outputTag);

    assertEquals(2, result.size());

    WindowedValue<KV<String, Long>> item0 = result.get(0);
    assertEquals("k", item0.getValue().getKey());
    assertEquals((Long) 7L, item0.getValue().getValue());
    assertEquals(new Instant(14), item0.getTimestamp());
    assertThat(item0.getWindows(), Matchers.contains(window(0, 15)));

    WindowedValue<KV<String, Long>> item1 = result.get(1);
    assertEquals("k", item1.getValue().getKey());
    assertEquals((Long) 3L, item1.getValue().getValue());
    assertEquals(new Instant(24), item1.getTimestamp());
    assertThat(item1.getWindows(), Matchers.contains(window(15, 25)));
  }

  private DoFnRunner<TimerOrElement<KV<String, String>>, KV<String, Iterable<String>>, List>
      makeRunner(WindowFn<? super String, IntervalWindow> windowFn) {
    return makeRunner(windowFn, null, StringUtf8Coder.of());
  }

  private DoFnRunner<TimerOrElement<KV<String, Long>>, KV<String, Long>, List> makeRunner(
        WindowFn<? super String, IntervalWindow> windowFn,
        KeyedCombineFn<String, Long, ?, Long> combineFn) {
    return makeRunner(windowFn, combineFn, BigEndianLongCoder.of());
  }

  private <VI, VO> DoFnRunner<TimerOrElement<KV<String, VI>>, KV<String, VO>, List> makeRunner(
        WindowFn<? super String, IntervalWindow> windowFn,
        KeyedCombineFn<String, VI, ?, VO> combineFn,
        Coder<VI> inputValueCoder) {
    StreamingGroupAlsoByWindowsDoFn<String, VI, VO, IntervalWindow> fn =
        StreamingGroupAlsoByWindowsDoFn.create(
            windowFn, combineFn, StringUtf8Coder.of(), inputValueCoder);

    return
        DoFnRunner.createWithListOutputs(
            PipelineOptionsFactory.create(),
            fn,
            PTuple.empty(),
            (TupleTag<KV<String, VO>>) (TupleTag) outputTag,
            new ArrayList<TupleTag<?>>(),
            execContext.createStepContext("merge"),
            counters.getAddCounterMutator(),
            windowFn);
  }

  private BoundedWindow window(long start, long end) {
    return new IntervalWindow(new Instant(start), new Instant(end));
  }
}
