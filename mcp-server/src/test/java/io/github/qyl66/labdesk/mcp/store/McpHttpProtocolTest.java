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

import io.github.qyl66.labdesk.mcp.config.McpServerConfig;
import io.github.qyl66.labdesk.mcp.config.McpAccessConfiguration;
import io.github.qyl66.labdesk.mcp.executor.McpToolResults;
import io.github.qyl66.labdesk.mcp.executor.common.CurrentDateMcpExecutor;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = McpHttpProtocolTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "labdesk.mcp-token=store-mcp-test-token"})
@ActiveProfiles("store-agent")
class McpHttpProtocolTest {

    @LocalServerPort
    private int port;

    @Autowired
    private StoreBooking bookings;

    @Test
    void shouldDiscoverAndCallToolsWithUserMetaOverHttp() throws Exception {
        String url = "http://127.0.0.1:" + port + "/mcp";
        var unauthorized = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(unauthorized.statusCode()).isEqualTo(401);

        try (var client = McpClientBuilder.create("store-agent-test")
                .streamableHttpTransport(url)
                .header("X-LabDesk-Mcp-Token", "store-mcp-test-token")
                .buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));
            assertThat(client.listTools().block(Duration.ofSeconds(5)))
                    .extracting(tool -> tool.name())
                    .containsExactlyInAnyOrder("current_date", "store_product", "store_compare", "store_compatibility",
                            "store_policy", "store_service_centers", "store_demo_slots", "store_create_demo_booking",
                            "store_my_demo_bookings");

            CallToolResult date = client.callTool("current_date", Map.of("timezone", "UTC"))
                    .block(Duration.ofSeconds(5));
            assertThat(date.isError()).isFalse();
            assertThat(((TextContent) date.content().get(0)).text()).contains("当前日期");

            CallToolResult missingIdentity = client.callTool("store_my_demo_bookings", Map.of())
                    .block(Duration.ofSeconds(5));
            assertThat(missingIdentity.isError()).isTrue();
            assertThat(((TextContent) missingIdentity.content().get(0)).text()).contains("无法确认你的身份");

            when(bookings.mine("user-1")).thenReturn("DS-42");
            CallToolResult booking = client.callTool("store_my_demo_bookings", Map.of(),
                    Map.of(McpToolResults.META_USER_ID, "user-1"))
                    .block(Duration.ofSeconds(5));
            assertThat(booking.isError()).isFalse();
            assertThat(((TextContent) booking.content().get(0)).text()).isEqualTo("DS-42");
            verify(bookings).mine("user-1");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({McpServerConfig.class, McpAccessConfiguration.class, StoreTools.class})
    static class Application {

        @Bean
        StoreBooking storeBooking() {
            return mock(StoreBooking.class);
        }

        @Bean
        StoreCatalog storeCatalog() {
            return mock(StoreCatalog.class);
        }

        @Bean
        McpServerFeatures.SyncToolSpecification currentDateToolSpecification() {
            return new CurrentDateMcpExecutor().currentDateToolSpecification();
        }

    }
}
