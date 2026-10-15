/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.metric

import org.apache.gluten.backendsapi.velox.VeloxMetricsApi
import org.apache.gluten.metrics.{OperatorMetrics, WindowMetricsUpdater}

import org.apache.spark.{SparkContext, SparkFunSuite, TaskContext}
import org.apache.spark.executor.TaskMetrics

import org.mockito.Mockito.{mock, when}

class GlutenWindowMetricsUpdaterSuite extends SparkFunSuite {
  private def newUpdater(): WindowMetricsUpdater = {
    val sparkContext = mock(classOf[SparkContext])
    when(sparkContext.cleaner).thenReturn(None)
    val metrics = new VeloxMetricsApi().genWindowTransformerMetrics(sparkContext)
    new WindowMetricsUpdater(metrics)
  }

  private def spillMetrics(memoryBytes: Long, diskBytes: Long): OperatorMetrics = {
    val metrics = new OperatorMetrics()
    metrics.spilledInputBytes = memoryBytes
    metrics.spilledBytes = diskBytes
    metrics
  }

  private def withTaskMetrics(f: TaskMetrics => Unit): Unit = {
    val previousContext = TaskContext.get()
    val context: TaskContext = TaskContext.empty()
    TaskContext.setTaskContext(context)
    try {
      f(context.taskMetrics())
    } finally {
      TaskContext.setTaskContext(previousContext)
    }
  }

  test("window spill metrics accumulate in task metrics") {
    withTaskMetrics {
      taskMetrics =>
        taskMetrics.incMemoryBytesSpilled(100)
        taskMetrics.incDiskBytesSpilled(200)
        val updater = newUpdater()

        updater.updateNativeMetrics(spillMetrics(1024, 256))
        updater.updateNativeMetrics(spillMetrics(2048, 512))

        assert(taskMetrics.memoryBytesSpilled == 3172)
        assert(taskMetrics.diskBytesSpilled == 968)
        assert(updater.metrics("spilledBytes").value == 768)
    }
  }

  test("missing native metrics and no spill preserve task metrics") {
    withTaskMetrics {
      taskMetrics =>
        taskMetrics.incMemoryBytesSpilled(100)
        taskMetrics.incDiskBytesSpilled(200)
        val updater = newUpdater()

        updater.updateNativeMetrics(null)
        updater.updateNativeMetrics(new OperatorMetrics())

        assert(taskMetrics.memoryBytesSpilled == 100)
        assert(taskMetrics.diskBytesSpilled == 200)
    }
  }

  test("window SQL metrics can be updated outside a task") {
    assert(TaskContext.get() == null)
    val updater = newUpdater()

    updater.updateNativeMetrics(spillMetrics(1024, 256))

    assert(updater.metrics("spilledBytes").value == 256)
    assert(TaskContext.get() == null)
  }
}
