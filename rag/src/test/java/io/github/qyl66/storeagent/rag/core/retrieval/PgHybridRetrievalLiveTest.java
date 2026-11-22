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
 *
 * Modified for Digital Store Agent.
 */

package io.github.qyl66.storeagent.rag.core.retrieval;

import io.github.qyl66.storeagent.framework.convention.RetrievedChunk;
import io.github.qyl66.storeagent.infra.embedding.EmbeddingService;
import io.github.qyl66.storeagent.rag.config.SearchChannelProperties;
import io.github.qyl66.storeagent.rag.core.keyword.PgKeywordRetrieverService;
import io.github.qyl66.storeagent.rag.core.retrieval.channel.KeywordSearchChannel;
import io.github.qyl66.storeagent.rag.core.retrieval.channel.RetrievalScope;
import io.github.qyl66.storeagent.rag.core.retrieval.channel.RetrievalScopeResolver;
import io.github.qyl66.storeagent.rag.core.retrieval.channel.VectorSearchChannel;
import io.github.qyl66.storeagent.rag.core.retrieval.postprocessor.DeduplicationPostProcessor;
import io.github.qyl66.storeagent.rag.core.retrieval.postprocessor.FusionPostProcessor;
import io.github.qyl66.storeagent.rag.core.vector.PgVectorRetrieverService;
import io.github.qyl66.storeagent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "STORE_AGENT_TEST_PG_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/store_eval")
class PgHybridRetrievalLiveTest {

    @Test
    void mergesBothRealDatabaseChannelsWithoutAnElasticsearchClient() {
        String question = "PB2165给红米K90充电能不能满165W";
        var source = new DriverManagerDataSource(System.getenv("STORE_AGENT_TEST_PG_URL"), "postgres", "");
        var jdbc = new JdbcTemplate(source);
        String vector = jdbc.queryForObject("SELECT embedding::text FROM t_knowledge_vector WHERE id = 'pb2165#01'", String.class);
        List<Float> vectorValues = Arrays.stream(vector.substring(1, vector.length() - 1).split(","))
                .map(Float::valueOf)
                .toList();
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.embed(question)).thenReturn(vectorValues);
        var intent = new SubQuestionIntent(question, List.of());
        RetrievalScopeResolver scopeResolver = mock(RetrievalScopeResolver.class);
        when(scopeResolver.resolve(List.of(intent))).thenReturn(RetrievalScope.global(0, List.of("store-eval")));
        var properties = new SearchChannelProperties();
        properties.getChannels().getKeyword().setEnabled(true);

        new ApplicationContextRunner()
                .withPropertyValues("rag.vector.type=pg", "rag.keyword.type=pg")
                .withUserConfiguration(PgKeywordRetrieverService.class, PgVectorRetrieverService.class,
                        KeywordSearchChannel.class, VectorSearchChannel.class, DeduplicationPostProcessor.class,
                        FusionPostProcessor.class, MultiChannelRetrievalEngine.class)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(jdbc))
                .withBean(EmbeddingService.class, () -> embedding)
                .withBean(RetrievalScopeResolver.class, () -> scopeResolver)
                .withBean(Executor.class, () -> Runnable::run)
                .withBean(SearchChannelProperties.class, () -> properties)
                .run(context -> {
                    assertThat(context).hasSingleBean(KeywordSearchChannel.class);
                    var engine = context.getBean(MultiChannelRetrievalEngine.class);
                    List<RetrievedChunk> result = engine.retrieveKnowledgeChannels(
                            intent, new RetrievalBudget(20, 20, 5)).chunks();
                    assertThat(result).extracting(RetrievedChunk::getId).contains("pb2165#01").doesNotHaveDuplicates();
                    assertThat(result).allMatch(chunk -> "store-eval".equals(chunk.getCollectionName()));
                    assertThat(result.stream().filter(chunk -> "pb2165#01".equals(chunk.getId()))
                            .findFirst().orElseThrow().getScore()).isGreaterThan(1f / (properties.getFusion().getRrfK() + 1));
                });
    }
}
