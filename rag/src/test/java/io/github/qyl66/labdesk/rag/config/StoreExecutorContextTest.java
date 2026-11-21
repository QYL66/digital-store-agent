/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.qyl66.labdesk.rag.config;

import com.alibaba.ttl.threadpool.TtlExecutors;
import io.github.qyl66.labdesk.framework.context.LoginUser;
import io.github.qyl66.labdesk.framework.context.UserContext;
import io.github.qyl66.labdesk.framework.trace.RagTraceContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StoreExecutorContextTest {

    @Test
    void retrievalAndChunkWorkersCarryContextWithoutLeakingToNextRequest() throws Exception {
        ThreadPoolExecutorConfig config = new ThreadPoolExecutorConfig();
        Executor retrieval = config.ragRetrievalExecutor();
        Executor chunking = config.knowledgeChunkExecutor();
        ThreadPoolExecutor retrievalPool = (ThreadPoolExecutor) TtlExecutors.unwrap(retrieval);
        ThreadPoolExecutor chunkPool = (ThreadPoolExecutor) TtlExecutors.unwrap(chunking);
        retrievalPool.setCorePoolSize(1);
        retrievalPool.setMaximumPoolSize(1);
        try {
            UserContext.set(LoginUser.builder().userId("user-a").build());
            RagTraceContext.setTraceId("trace-a");
            Snapshot first = capture(retrieval);
            Snapshot chunk = capture(chunking);
            assertThat(first.user()).isEqualTo("user-a");
            assertThat(first.trace()).isEqualTo("trace-a");
            assertThat(chunk.worker()).isNotEqualTo(first.worker());
            assertThat(chunk.trace()).isEqualTo("trace-a");
            UserContext.clear();
            RagTraceContext.setTraceId(null);
            Snapshot next = capture(retrieval);
            assertThat(next.worker()).isEqualTo(first.worker());
            assertThat(next.user()).isNull();
            assertThat(next.trace()).isNull();
        } finally {
            UserContext.clear();
            RagTraceContext.setTraceId(null);
            retrievalPool.shutdownNow();
            chunkPool.shutdownNow();
        }
    }

    private Snapshot capture(Executor executor) throws Exception {
        return CompletableFuture.supplyAsync(() -> new Snapshot(
                UserContext.getUserId(), RagTraceContext.getTraceId(), Thread.currentThread().getName()), executor)
                .get(5, TimeUnit.SECONDS);
    }

    private record Snapshot(String user, String trace, String worker) {}
}
