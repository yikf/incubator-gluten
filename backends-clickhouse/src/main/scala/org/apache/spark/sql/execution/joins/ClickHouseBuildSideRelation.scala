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
package org.apache.spark.sql.execution.joins

import org.apache.gluten.execution.{BroadCastHashJoinContext, ColumnarNativeIterator}
import org.apache.gluten.utils.{IteratorUtil, PlanNodesUtil}
import org.apache.gluten.vectorized._

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, UnsafeProjection}
import org.apache.spark.sql.catalyst.plans.physical.{BroadcastMode, IdentityBroadcastMode}
import org.apache.spark.sql.execution.utils.CHExecUtil
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.storage.CHShuffleReadStreamFactory

import scala.collection.JavaConverters._

case class ClickHouseBuildSideRelation(
    mode: BroadcastMode,
    output: Seq[Attribute],
    batches: Array[Byte],
    numOfRows: Long,
    newBuildKeys: Seq[Expression] = Seq.empty,
    hasNullKeyValues: Boolean = false)
  extends BuildSideRelation
  with Logging {

  override def deserialized: Iterator[ColumnarBatch] = Iterator.empty

  override def asReadOnlyCopy(): ClickHouseBuildSideRelation = this

  private var existHashTableData: Long = 0L
  private var existBroadCastHashJoinContext: BroadCastHashJoinContext = null

  def buildHashTable(
      broadCastContext: BroadCastHashJoinContext): (Long, ClickHouseBuildSideRelation) =
    synchronized {
      if (!couldReuseHashTableData(broadCastContext)) {
        logDebug(
          s"BHJ value size: " +
            s"${broadCastContext.buildHashTableId} = ${batches.length}")
        // Build the hash table
        existHashTableData = StorageJoinBuilder.build(
          batches,
          numOfRows,
          broadCastContext,
          newBuildKeys.asJava,
          output.asJava,
          hasNullKeyValues)
        existBroadCastHashJoinContext = broadCastContext
        (existHashTableData, this)
      } else {
        (StorageJoinBuilder.nativeCloneBuildHashTable(existHashTableData), null)
      }
    }

  def reset(): Unit = synchronized {
    existHashTableData = 0
    existBroadCastHashJoinContext = null
  }

  private def couldReuseHashTableData(broadCastContext: BroadCastHashJoinContext): Boolean = {
    if (existHashTableData != 0) {
      existBroadCastHashJoinContext.joinType == broadCastContext.joinType &&
      existBroadCastHashJoinContext.hasMixedFiltCondition == broadCastContext.hasMixedFiltCondition
    }
    false
  }

  /**
   * Transform columnar broadcast value to Array[InternalRow] by key.
   *
   * @return
   */
  override def transform(key: Expression): Array[InternalRow] = {
    // native block reader
    val blockReader = new CHStreamReader(CHShuffleReadStreamFactory.create(batches, true))
    val broadCastIter: Iterator[ColumnarBatch] = IteratorUtil.createBatchIterator(blockReader)

    val transformProjections = mode match {
      case HashedRelationBroadcastMode(k, _) => k
      case IdentityBroadcastMode => output
    }

    // Expression compute, return block iterator
    val expressionEval = new SimpleExpressionEval(
      new ColumnarNativeIterator(broadCastIter.asJava),
      PlanNodesUtil.genProjectionsPlanNode(transformProjections, output))

    val proj = UnsafeProjection.create(Seq(key))

    try {
      // convert columnar to row
      asScalaIterator(expressionEval).flatMap {
        block =>
          val batch = new CHNativeBlock(block)
          if (batch.numRows == 0) {
            Iterator.empty
          } else {
            CHExecUtil
              .getRowIterFromSparkRowInfo(block, batch.numColumns(), batch.numRows())
              .map(proj)
              .map(row => row.copy())
          }
      }.toArray
    } finally {
      blockReader.close()
      expressionEval.close()
    }
  }
}
