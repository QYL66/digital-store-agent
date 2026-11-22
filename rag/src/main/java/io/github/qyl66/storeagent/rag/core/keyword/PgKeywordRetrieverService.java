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

package io.github.qyl66.storeagent.rag.core.keyword;

import io.github.qyl66.storeagent.framework.convention.RetrievedChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "rag.keyword", name = "type", havingValue = "pg")
public class PgKeywordRetrieverService implements KeywordRetrieverService {

    private final NamedParameterJdbcTemplate jdbc;
    private final String searchSql;

    public PgKeywordRetrieverService(NamedParameterJdbcTemplate jdbc) throws IOException {
        this.jdbc = jdbc;
        searchSql = new ClassPathResource("keyword/search-pg.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    @Override
    public List<RetrievedChunk> search(String query, List<String> collectionNames, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("limit", topK);
        String sql = searchSql;
        if (collectionNames != null && !collectionNames.isEmpty()) {
            sql = sql.replace("/* scope */ TRUE", "v.collection_name IN (:collections)");
            parameters.addValue("collections", collectionNames);
        }
        return jdbc.query(sql, parameters, (rs, row) -> RetrievedChunk.builder()
                .id(rs.getString("id"))
                .text(rs.getString("content"))
                .collectionName(rs.getString("collection_name"))
                .score(rs.getFloat("score"))
                .build());
    }
}
