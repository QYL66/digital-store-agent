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

import io.github.qyl66.labdesk.mcp.config.McpToolAnnotations;
import io.github.qyl66.labdesk.mcp.executor.McpToolResults;
import io.github.qyl66.labdesk.mcp.executor.McpToolSchema;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

import static io.github.qyl66.labdesk.mcp.executor.McpToolSchema.string;

@Slf4j
@Component
@Profile("store-agent")
@RequiredArgsConstructor
class StoreTools {
    private final StoreCatalog catalog;
    private final StoreBooking booking;

    @Bean
    SyncToolSpecification product() {
        return read("store_product", "按具体型号查手机或充电配件的公开规格。kind 为 phone 或 accessory。",
                McpToolSchema.object().required(string("kind", "phone 或 accessory"))
                        .required(field("name", "机型或配件名称")), request -> {
                    String kind = value(request, "kind");
                    if (!kind.equals("phone") && !kind.equals("accessory")) {
                        throw new IllegalArgumentException("商品类别必须为 phone 或 accessory。");
                    }
                    return catalog.require(kind, value(request, "name")).describe();
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
    SyncToolSpecification availability() {
        return read("store_demo_slots", "查询本地演示维修时段。日期为北京时间，七天内。",
                McpToolSchema.object().required(field("model", "具体机型")).required(field("city", "城市"))
                        .required(field("date", "预约日期 yyyy-MM-dd")),
                request -> booking.availability(value(request, "model"), value(request, "city"),
                        LocalDate.parse(value(request, "date"))));
    }

    @Bean
    SyncToolSpecification createBooking() {
        String id = "store_create_demo_booking";
        return tool(id, "经用户确认后创建本地演示维修预约。网点编号和时间必须来自查号源结果。",
                McpToolSchema.object().required(field("model", "具体机型")).required(field("symptom", "故障现象"))
                        .required(field("centerCode", "演示网点")).required(field("date", "预约日期 yyyy-MM-dd"))
                        .required(field("time", "北京时间 HH:mm")), false, request -> {
                    String user = McpToolResults.userId(request);
                    if (user == null) {
                        return McpToolResults.identityRequired(id);
                    }
                    return McpToolResults.success(booking.create(user, value(request, "model"),
                            value(request, "centerCode"), LocalDate.parse(value(request, "date")),
                            LocalTime.parse(value(request, "time")), value(request, "symptom")));
                });
    }

    @Bean
    SyncToolSpecification myBookings() {
        String id = "store_my_demo_bookings";
        return tool(id, "查询当前用户自己的本地演示维修预约。", McpToolSchema.object(), true, request -> {
            String user = McpToolResults.userId(request);
            return user == null ? McpToolResults.identityRequired(id) : McpToolResults.success(booking.mine(user));
        });
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
        return tool(id, description, schema, true, request -> McpToolResults.success(handler.apply(request)));
    }

    private static SyncToolSpecification tool(String id, String description, McpToolSchema schema, boolean readOnly,
                                               Function<CallToolRequest, CallToolResult> handler) {
        return SyncToolSpecification.builder().tool(Tool.builder().name(id).description(description)
                        .inputSchema(schema.build()).annotations(readOnly ? McpToolAnnotations.READ_ONLY
                                : McpToolAnnotations.WRITE).build())
                .callHandler((exchange, request) -> {
                    try {
                        return handler.apply(request);
                    } catch (DateTimeParseException e) {
                        return McpToolResults.error("日期或时间不符合要求。");
                    } catch (IllegalArgumentException e) {
                        return McpToolResults.error(e.getMessage());
                    } catch (RuntimeException e) {
                        log.error("Store tool {} failed", id, e);
                        return McpToolResults.failure("商城工具", e);
                    }
                }).build();
    }
}
