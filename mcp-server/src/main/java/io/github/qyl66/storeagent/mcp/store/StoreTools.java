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

package io.github.qyl66.storeagent.mcp.store;

import io.github.qyl66.storeagent.mcp.config.McpToolAnnotations;
import io.github.qyl66.storeagent.mcp.executor.McpToolResults;
import io.github.qyl66.storeagent.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Function;

import static io.github.qyl66.storeagent.mcp.executor.McpToolSchema.string;

@Slf4j
@Component
@RequiredArgsConstructor
class StoreTools {
    private final StoreCatalog catalog;

    @Bean
    SyncToolSpecification product() {
        return read("store_product", "按具体型号查手机或充电配件的公开规格。kind 为 phone 或 accessory。",
                McpToolSchema.object().required(string("kind", "phone 或 accessory"))
                        .required(field("name", "机型或配件名称")), request -> {
                    return catalog.require(productKind(request), value(request, "name")).describe();
                });
    }

    @Bean
    SyncToolSpecification compare() {
        return read("store_compare", "按官方字段并排比较两款手机，返回带来源的规格表。",
                McpToolSchema.object().required(field("first", "第一款手机")).required(field("second", "第二款手机")),
                request -> catalog.compare(value(request, "first"), value(request, "second")));
    }

    @Bean
    SyncToolSpecification compatibility() {
        return read("store_compatibility", "查询手机与充电配件的快充证据和证据粒度。",
                McpToolSchema.object().required(field("model", "具体机型"))
                        .required(field("accessory", "具体配件")),
                request -> catalog.compatibility(value(request, "model"), value(request, "accessory")));
    }

    @Bean
    SyncToolSpecification policy() {
        return read("store_policy", "查询公开政策。topic 可为 优惠券、价保、售后。",
                McpToolSchema.object().required(field("topic", "具体政策")),
                request -> catalog.require("policy", value(request, "topic")).describe());
    }

    @Bean
    SyncToolSpecification serviceCenters() {
        return read("store_service_centers", "查询已整理的官方授权维修网点，或提供官网查询入口。",
                McpToolSchema.object().required(field("city", "城市")),
                request -> catalog.serviceCenters(value(request, "city")));
    }

    @Bean
    SyncToolSpecification searchCatalog() {
        return read("store_catalog", "按品类、系列、型号或别名浏览已收录商品。快照不提供实时上新排名、价格和库存。",
                McpToolSchema.object().required(field("kind", "phone 或 accessory"))
                        .optional(field("keyword", "型号、系列或别名，省略则浏览该品类")), request -> {
                    String kind = productKind(request);
                    return catalog.search(kind, McpToolResults.args(request).containsKey("keyword") ? value(request, "keyword") : "");
                });
    }

    @Bean
    SyncToolSpecification support() {
        return read("store_support", "根据已知故障类型查询公开排查资料，如充不上电、充电慢。",
                McpToolSchema.object().required(field("symptom", "具体故障现象")),
                request -> catalog.require("fault", value(request, "symptom")).describe());
    }

    private static String productKind(CallToolRequest request) {
        String kind = value(request, "kind");
        if (!kind.equals("phone") && !kind.equals("accessory")) {
            throw new IllegalArgumentException("商品类别必须为 phone 或 accessory。");
        }
        return kind;
    }

    private static McpToolSchema.Property field(String name, String title) {
        return string(name, title).title(title);
    }

    private static String value(CallToolRequest request, String name) {
        Object value = McpToolResults.args(request).get(name);
        if (!(value instanceof String text) || text.isBlank() || text.length() > 200) {
            throw new IllegalArgumentException("请提供有效的 " + name + "。");
        }
        return text.strip();
    }

    private static SyncToolSpecification read(String id, String description, McpToolSchema schema,
                                               Function<CallToolRequest, String> handler) {
        return SyncToolSpecification.builder().tool(Tool.builder().name(id).description(description)
                        .inputSchema(schema.build()).annotations(McpToolAnnotations.READ_ONLY).build())
                .callHandler((exchange, request) -> {
                    try {
                        return McpToolResults.success(handler.apply(request));
                    } catch (IllegalArgumentException e) {
                        return McpToolResults.error(e.getMessage());
                    } catch (RuntimeException e) {
                        log.error("Store tool {} failed", id, e);
                        return McpToolResults.failure("商城工具", e);
                    }
                }).build();
    }
}
