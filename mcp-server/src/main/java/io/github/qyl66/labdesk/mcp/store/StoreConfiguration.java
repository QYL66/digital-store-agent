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

package io.github.qyl66.labdesk.mcp.store;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.util.Objects;

@Configuration
@Profile("store-agent")
class StoreConfiguration {
    @Bean
    StoreBooking storeBooking(JdbcTemplate jdbc, StoreCatalog catalog) {
        var schema = new ResourceDatabasePopulator(new ClassPathResource("db/store-agent.sql"));
        schema.setSqlScriptEncoding("UTF-8");
        schema.execute(Objects.requireNonNull(jdbc.getDataSource()));
        return new StoreBooking(jdbc, Clock.systemUTC(), catalog);
    }
}
