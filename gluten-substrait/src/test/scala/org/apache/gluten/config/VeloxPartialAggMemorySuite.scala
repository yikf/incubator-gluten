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
package org.apache.gluten.config

import org.scalatest.funsuite.AnyFunSuiteLike

class VeloxPartialAggMemorySuite extends AnyFunSuiteLike {

  private val offHeapKey = GlutenCoreConfig.COLUMNAR_TASK_OFFHEAP_SIZE_IN_BYTES.key
  private val maxKey = "spark.gluten.sql.columnar.backend.velox.maxPartialAggregationMemory"
  private val maxRatioKey =
    "spark.gluten.sql.columnar.backend.velox.maxPartialAggregationMemoryRatio"
  private val maxExtKey =
    "spark.gluten.sql.columnar.backend.velox.maxExtendedPartialAggregationMemory"
  private val maxExtRatioKey =
    "spark.gluten.sql.columnar.backend.velox.maxExtendedPartialAggregationMemoryRatio"

  private val floor = 1 << 24 // 16MB
  private val extFloor = 1 << 26 // 64MB

  test("default ratios applied against per-task off-heap size") {
    val offHeap = 4L * 1024 * 1024 * 1024 // 4GB
    val resolved =
      GlutenConfig.resolveVeloxPartialAggMemoryConf(Map(offHeapKey -> offHeap.toString))
    assert(resolved(maxKey) === ((0.1 * offHeap).toLong).toString)
    assert(resolved(maxExtKey) === ((0.15 * offHeap).toLong).toString)
  }

  test("custom ratios override defaults") {
    val offHeap = 2L * 1024 * 1024 * 1024 // 2GB
    val resolved = GlutenConfig.resolveVeloxPartialAggMemoryConf(
      Map(offHeapKey -> offHeap.toString, maxRatioKey -> "0.25", maxExtRatioKey -> "0.5"))
    assert(resolved(maxKey) === ((0.25 * offHeap).toLong).toString)
    assert(resolved(maxExtKey) === ((0.5 * offHeap).toLong).toString)
  }

  test("explicit byte value overrides ratio and accepts unit suffix") {
    val offHeap = 4L * 1024 * 1024 * 1024
    val resolved = GlutenConfig.resolveVeloxPartialAggMemoryConf(
      Map(offHeapKey -> offHeap.toString, maxKey -> "256m", maxExtKey -> "512m"))
    assert(resolved(maxKey) === (256L * 1024 * 1024).toString)
    assert(resolved(maxExtKey) === (512L * 1024 * 1024).toString)
  }

  test("floor is enforced when ratio result is tiny") {
    val offHeap = 1L * 1024 * 1024 // 1MB, so ratio results fall below both floors
    val resolved =
      GlutenConfig.resolveVeloxPartialAggMemoryConf(Map(offHeapKey -> offHeap.toString))
    assert(resolved(maxKey) === floor.toString)
    assert(resolved(maxExtKey) === extFloor.toString)
  }

  test("missing per-task off-heap falls back to Long.MaxValue like native default") {
    val resolved = GlutenConfig.resolveVeloxPartialAggMemoryConf(Map.empty)
    assert(resolved(maxKey) === ((0.1 * Long.MaxValue).toLong).toString)
    assert(resolved(maxExtKey) === ((0.15 * Long.MaxValue).toLong).toString)
  }
}
