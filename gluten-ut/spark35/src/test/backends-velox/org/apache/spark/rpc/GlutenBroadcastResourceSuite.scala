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
package org.apache.spark.rpc

import org.apache.spark.{SparkConf, SparkEnv, SparkFunSuite}
import org.apache.spark.rpc.GlutenRpcMessages.GlutenCleanExecutionResource

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doAnswer, mock, when}

import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors, LinkedBlockingQueue, TimeUnit}

import scala.collection.JavaConverters._

class GlutenBroadcastResourceSuite extends SparkFunSuite {
  private def withResourceRegistry(
      f: (String, LinkedBlockingQueue[GlutenCleanExecutionResource]) => Unit): Unit = {
    val previousEnv = SparkEnv.get
    val env = mock(classOf[SparkEnv])
    when(env.conf).thenReturn(new SparkConf(false))
    SparkEnv.set(env)
    val executionId = UUID.randomUUID().toString
    val messages = new LinkedBlockingQueue[GlutenCleanExecutionResource]()
    val endpoint = mock(classOf[RpcEndpointRef])
    doAnswer {
      invocation =>
        val message = invocation.getArgument[GlutenCleanExecutionResource](0)
        if (message.executionId.startsWith(executionId)) {
          messages.add(message)
        }
        null
    }.when(endpoint).send(any[GlutenCleanExecutionResource])

    GlutenDriverEndpoint.executorDataMap.put(executionId, new ExecutorData(endpoint))
    try {
      f(executionId, messages)
    } finally {
      GlutenDriverEndpoint.invalidateResourceRelation(executionId)
      GlutenDriverEndpoint.invalidateResourceRelation(executionId + "-other")
      GlutenDriverEndpoint.executorDataMap.remove(executionId)
      SparkEnv.set(previousEnv)
    }
  }

  private def cleanupMessage(
      messages: LinkedBlockingQueue[GlutenCleanExecutionResource]): GlutenCleanExecutionResource = {
    val message = messages.poll(10, TimeUnit.SECONDS)
    assert(message != null, "Expected an execution resource cleanup message")
    message
  }

  test("concurrent registration preserves all broadcast resource IDs for cleanup") {
    withResourceRegistry {
      (executionId, messages) =>
        val threads = 8
        val resourcesPerThread = 2000
        val pool = Executors.newFixedThreadPool(threads)
        val start = new CountDownLatch(1)
        try {
          val registrations = (0 until threads).map {
            thread =>
              pool.submit(new Runnable {
                override def run(): Unit = {
                  start.await()
                  for (resource <- 0 until resourcesPerThread) {
                    GlutenDriverEndpoint.collectResources(executionId, s"$thread-$resource")
                  }
                }
              })
          }
          start.countDown()
          registrations.foreach(_.get(10, TimeUnit.SECONDS))
        } finally {
          pool.shutdownNow()
          assert(pool.awaitTermination(10, TimeUnit.SECONDS))
        }

        GlutenDriverEndpoint.invalidateResourceRelation(executionId)
        val message = cleanupMessage(messages)
        val expected = (for {
          thread <- 0 until threads
          resource <- 0 until resourcesPerThread
        } yield s"$thread-$resource").toSet
        val resourceCount = message.broadcastHashIds.size()
        assert(resourceCount == expected.size)
        assert(message.broadcastHashIds.asScala.toSet == expected)
    }
  }

  test("resource registration deduplicates IDs and keeps executions separate") {
    withResourceRegistry {
      (executionId, messages) =>
        val otherExecutionId = executionId + "-other"
        GlutenDriverEndpoint.collectResources(executionId, "shared")
        GlutenDriverEndpoint.collectResources(executionId, "shared")
        GlutenDriverEndpoint.collectResources(executionId, "first-only")
        GlutenDriverEndpoint.collectResources(otherExecutionId, "shared")
        GlutenDriverEndpoint.collectResources(otherExecutionId, "second-only")

        GlutenDriverEndpoint.invalidateResourceRelation(executionId)
        GlutenDriverEndpoint.invalidateResourceRelation(otherExecutionId)
        val resources = Seq(cleanupMessage(messages), cleanupMessage(messages))
          .map(m => m.executionId -> m.broadcastHashIds.asScala.toSet)
          .toMap
        assert(resources(executionId) == Set("shared", "first-only"))
        assert(resources(otherExecutionId) == Set("shared", "second-only"))
    }
  }
}
