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

package io.github.qyl66.labdesk.rag.core.graph;

import io.github.qyl66.labdesk.framework.convention.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
public class GraphDocumentMaintenance {

    private static final String GRAPH_LOCK = "rag:graph:document-maintenance";
    private static final String PENDING_WRITES = "rag:graph:pending-document-writes";
    private static final String RETIRED_DOCUMENTS = """
            SELECT d.id FROM t_knowledge_document d
            JOIN t_knowledge_base kb ON kb.id = d.kb_id
            WHERE (d.deleted <> 0 OR kb.deleted <> 0)
            """;

    private final LightRagClient lightRagClient;
    private final NamedParameterJdbcTemplate jdbc;
    private final RedissonClient redissonClient;

    public void submitDocument(String source, String text) {
        if (text.isBlank()) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    queueAndTryWrite(source, text);
                }
            });
        } else {
            queueAndTryWrite(source, text);
        }
    }

    private void queueAndTryWrite(String source, String text) {
        try {
            RMap<String, String> pending = pendingWrites();
            pending.fastPut(source, text);
            RLock lock = redissonClient.getLock(GRAPH_LOCK);
            if (lock.tryLock()) {
                try {
                    writePendingSource(pending, source);
                } finally {
                    lock.unlock();
                }
            }
        } catch (RuntimeException e) {
            log.warn("图谱写入暂未完成 source={}", source, e);
        }
    }

    private RMap<String, String> pendingWrites() {
        return redissonClient.getMap(PENDING_WRITES, StringCodec.INSTANCE);
    }

    private boolean writePendingSource(RMap<String, String> pending, String source) {
        String text = pending.get(source);
        if (text == null) {
            return true;
        }
        if (!lightRagClient.insertText(text, source)) {
            return false;
        }
        pending.remove(source, text);
        return true;
    }

    public GraphEvidence omitRetired(GraphEvidence evidence) {
        Set<String> ids = Stream.concat(evidence.matched().stream(), evidence.unmatched().stream())
                .map(RetrievedChunk::getDocId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return evidence;
        }
        Set<String> retired = Set.copyOf(jdbc.queryForList(
                RETIRED_DOCUMENTS + "AND d.id IN (:ids)", Map.of("ids", ids), String.class));
        if (retired.isEmpty()) {
            return evidence;
        }
        return new GraphEvidence(
                evidence.matched().stream().filter(chunk -> !isRetired(chunk, retired)).toList(),
                evidence.unmatched().stream().filter(chunk -> !isRetired(chunk, retired)).toList());
    }

    private boolean isRetired(RetrievedChunk chunk, Set<String> retired) {
        return chunk.getDocId() != null && retired.contains(chunk.getDocId());
    }

    /**
     * PostgreSQL soft deletes remain available across process restarts. Recheck them until
     * LightRAG actually removes the corresponding sources.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void reconcile() {
        RLock lock = redissonClient.getLock(GRAPH_LOCK);
        if (!lock.tryLock()) {
            return;
        }
        try {
            Set<String> docs = Set.copyOf(jdbc.queryForList(RETIRED_DOCUMENTS, Map.of(), String.class));
            RMap<String, String> pending = pendingWrites();
            for (String source : pending.keySet().stream().limit(32).toList()) {
                GraphFileSource parsed = GraphFileSource.parse(source);
                if (parsed != null && docs.contains(parsed.docId())) {
                    pending.fastRemove(source);
                } else if (!writePendingSource(pending, source)) {
                    return;
                }
            }
            if (pending.isEmpty()) {
                lightRagClient.deleteRetiredDocuments(docs);
            }
        } finally {
            lock.unlock();
        }
    }
}
