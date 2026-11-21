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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qyl66.labdesk.framework.convention.RetrievedChunk;
import io.github.qyl66.labdesk.rag.config.GraphProperties;
import io.github.qyl66.labdesk.rag.config.SearchChannelProperties;
import okhttp3.OkHttpClient;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LightRagClientTest {

    private MockWebServer server;
    private LightRagClient client;
    private SearchChannelProperties searchProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        GraphProperties properties = new GraphProperties();
        properties.getLightrag().setBaseUrl(server.url("/").toString());
        searchProperties = new SearchChannelProperties();
        client = new LightRagClient(new OkHttpClient(), objectMapper, properties, searchProperties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    @DisplayName("删库只命中库名精确归属的文档，不连带前缀重叠的别库")
    void deleteByCollectionMatchesExactCollectionOnly() throws Exception {
        // 回归：旧 contains("kb_") 谓词会把 kb_hr 的两篇文档一并选中，删 kb 连带删光 kb_hr 的图谱数据且不可逆
        server.enqueue(json("""
                {"documents":[
                  {"id":"doc-kb","file_path":"kb_1954071234567890100"},
                  {"id":"doc-kb-hr-1","file_path":"kb_hr_1954071234567890200"}
                ],"pagination":{"has_next":true}}
                """));
        server.enqueue(json("""
                {"documents":[
                  {"id":"doc-kb-2","file_path":"kb_1954071234567890400"},
                  {"id":"doc-kb-hr-2","file_path":"kb_hr_1954071234567890300"}
                ],"pagination":{"has_next":false}}
                """));
        server.enqueue(json("{}"));

        client.deleteByCollection("kb");

        RecordedRequest listRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(listRequest);
        assertEquals("/documents/paginated", listRequest.getTarget());
        assertEquals(1, objectMapper.readTree(listRequest.getBody().utf8()).path("page").asInt());
        RecordedRequest secondPage = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(secondPage);
        assertEquals("/documents/paginated", secondPage.getTarget());
        assertEquals(2, objectMapper.readTree(secondPage.getBody().utf8()).path("page").asInt());

        RecordedRequest deleteRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(deleteRequest, "kb 名下有文档，应发起删除请求");
        assertEquals("/documents/delete_document", deleteRequest.getTarget());
        assertEquals(List.of("doc-kb", "doc-kb-2"), docIdsOf(deleteRequest));
    }

    @Test
    @DisplayName("图谱忙时保留数据库退役记录，重新运行仍能清理且只命中旧文档")
    void retiredDocumentIsRetriedAfterBusyResponse() throws Exception {
        String inventory = """
                {"documents":[
                  {"id":"old-content","file_path":"store-agent-catalog_100"},
                  {"id":"new-content","file_path":"store-agent-catalog_101"}
                ],"pagination":{"has_next":false}}
                """;
        server.enqueue(json(inventory));
        server.enqueue(json("{\"status\":\"busy\"}"));
        server.enqueue(json(inventory));
        server.enqueue(json("{\"status\":\"deletion_started\"}"));
        server.enqueue(json("""
                {"documents":[{"id":"new-content","file_path":"store-agent-catalog_101"}],
                 "pagination":{"has_next":false}}
                """));

        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        RedissonClient redis = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        RMap<String, String> pending = pendingWrites(redis, new HashMap<>());
        when(jdbc.queryForList(anyString(), eq(Map.of()), eq(String.class))).thenReturn(List.of("100"));
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        new GraphDocumentMaintenance(client, jdbc, redis).reconcile();
        GraphDocumentMaintenance restarted = new GraphDocumentMaintenance(client, jdbc, redis);
        restarted.reconcile();
        restarted.reconcile();

        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals("/documents/paginated", server.takeRequest(2, TimeUnit.SECONDS).getTarget());
            RecordedRequest deleteRequest = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("/documents/delete_document", deleteRequest.getTarget());
            assertEquals(List.of("old-content"), docIdsOf(deleteRequest));
        }
        assertEquals("/documents/paginated", server.takeRequest(2, TimeUnit.SECONDS).getTarget());
        assertEquals(5, server.getRequestCount());
        assertTrue(pending.isEmpty());
    }

    @Test
    void documentWriteRejectedDuringCleanupIsRetried() throws Exception {
        String source = "store-agent-catalog_101";
        String text = "新资料";
        server.enqueue(json("{\"detail\":\"Pipeline is currently busy\"}").newBuilder().code(409).build());
        server.enqueue(json("{\"status\":\"success\"}"));
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        RedissonClient redis = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        Map<String, String> entries = new HashMap<>();
        RMap<String, String> pending = pendingWrites(redis, entries);
        when(jdbc.queryForList(anyString(), eq(Map.of()), eq(String.class))).thenReturn(List.of());
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(pending.fastPut(source, text)).thenAnswer(call -> entries.put(source, text) == null);
        when(pending.get(source)).thenAnswer(call -> entries.get(source));
        when(pending.remove(source, text)).thenAnswer(call -> entries.remove(source, text));

        new GraphDocumentMaintenance(client, jdbc, redis).submitDocument(source, text);
        assertEquals(Map.of(source, text), entries);
        new GraphDocumentMaintenance(client, jdbc, redis).reconcile();

        assertTrue(entries.isEmpty());
        for (int attempt = 0; attempt < 2; attempt++) {
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/documents/text", request.getTarget());
            assertEquals(source, objectMapper.readTree(request.getBody().utf8()).path("file_source").asText());
        }
    }

    @SuppressWarnings("unchecked")
    private RMap<String, String> pendingWrites(RedissonClient redis, Map<String, String> backing) {
        RMap<String, String> pending = mock(RMap.class);
        when(redis.<String, String>getMap(anyString(), eq(StringCodec.INSTANCE))).thenReturn(pending);
        when(pending.keySet()).thenAnswer(call -> backing.keySet());
        when(pending.isEmpty()).thenAnswer(call -> backing.isEmpty());
        return pending;
    }

    @Test
    @DisplayName("检索证据按库名精确归属切分主份与补充份")
    void retrieveByScopeSplitsByExactCollection() {
        // 回归：旧 contains 谓词会把 kb_hr 的证据归入 kb 的主份，跨库证据混进定向主路
        server.enqueue(json("""
                {"response":"ctx","references":[
                  {"reference_id":"r0","file_path":"kb_hr_1954071234567890200","content":["别库证据"]},
                  {"reference_id":"r1","file_path":"kb_1954071234567890100","content":["本库证据"]}
                ]}
                """));

        GraphEvidence evidence = client.retrieveByScope("报销流程", "mix", 10, List.of("kb"));

        assertEquals(List.of("r1"), ids(evidence.matched()));
        assertEquals(List.of("r0"), ids(evidence.unmatched()));
        assertEquals("1954071234567890100", evidence.matched().get(0).getDocId());
    }

    @Test
    @DisplayName("过滤生效时 references 缺失的兜底上下文被丢弃而非无主入池")
    void fallbackContextIsDroppedWhenFilteringByCollection() {
        // 兜底块无 file_path、无法归属，放行等于绕过有效库过滤
        server.enqueue(json("{\"response\":\"整段上下文\"}"));

        GraphEvidence evidence = client.retrieveByScope("报销流程", "mix", 10, List.of("kb"));

        assertTrue(evidence.matched().isEmpty());
        assertTrue(evidence.unmatched().isEmpty());
    }

    @Test
    @DisplayName("删除链路不受检索通道预算限制")
    void deletionIsNotBoundByChannelBudget() throws Exception {
        // 回归：检索预算若污染删除链路，大库文档枚举超预算会中断删除，静默留下图谱残留
        searchProperties.getChannels().setTimeoutMs(200);
        server.enqueue(json("""
                {"statuses":{"processed":[
                  {"id":"doc-kb","file_path":"kb_1954071234567890100"}
                ]}}
                """).newBuilder().bodyDelay(1, TimeUnit.SECONDS).build());
        server.enqueue(json("{}"));

        client.deleteByCollection("kb");

        server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest deleteRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(deleteRequest, "删除请求应照常发出，不被 200ms 检索预算掐断");
        assertEquals(List.of("doc-kb"), docIdsOf(deleteRequest));
    }

    @Test
    @DisplayName("客户端超时取通道级预算，超限调用降级为空证据")
    void clientTimeoutFollowsChannelBudget() {
        searchProperties.getChannels().setTimeoutMs(200);
        server.enqueue(json("{\"response\":\"ctx\"}").newBuilder().bodyDelay(1, TimeUnit.SECONDS).build());

        GraphEvidence evidence = client.retrieveByScope("报销流程", "mix", 10, List.of());

        assertTrue(evidence.matched().isEmpty());
    }

    private MockResponse json(String body) {
        return new MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private List<String> docIdsOf(RecordedRequest request) throws Exception {
        JsonNode body = objectMapper.readTree(request.getBody().utf8());
        List<String> ids = new ArrayList<>();
        body.path("doc_ids").forEach(node -> ids.add(node.asText()));
        return ids;
    }

    private static List<String> ids(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::getId).toList();
    }
}
