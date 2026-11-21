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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Component
@Profile("store-agent")
class StoreCatalog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    StoreCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Entry> entries(String kind) {
        return jdbc.query("""
                SELECT id, kind, name, series, source_url, checked_on, details
                FROM store_catalog WHERE kind = ? ORDER BY name, id
                """, (row, index) -> {
            try {
                return new Entry(row.getString("id"), row.getString("kind"), row.getString("name"),
                        row.getString("series"), row.getString("source_url"), row.getString("checked_on"),
                        json.readTree(row.getString("details")));
            } catch (IOException e) {
                throw new SQLException("Invalid catalogue record", e);
            }
        }, kind);
    }

    Entry require(String kind, String input) {
        String key = normalize(input);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("请提供具体型号或名称。");
        }
        List<Entry> candidates = entries(kind);
        List<Entry> exact = candidates.stream().filter(entry -> entry.matches(key)).toList();
        if (exact.size() == 1) {
            return exact.get(0);
        }
        List<String> options = (exact.isEmpty() ? candidates : exact).stream()
                .filter(entry -> !exact.isEmpty() || normalize(entry.name()).contains(key)
                        || normalize(entry.series()).equals(key) || normalize(entry.id()).startsWith(key))
                .map(Entry::name).distinct().limit(8).toList();
        if (!options.isEmpty()) {
            throw new IllegalArgumentException("请确认具体型号或类别：" + String.join("、", options));
        }
        throw new IllegalArgumentException("目录中未找到该条目，请核对官方名称。");
    }

    String compare(String first, String second) {
        Entry left = require("phone", first);
        Entry right = require("phone", second);
        if (left.id().equals(right.id())) {
            throw new IllegalArgumentException("请提供两款不同的机型。");
        }
        var table = new StringBuilder("| 规格 | ").append(left.name()).append(" | ").append(right.name())
                .append(" |\n| --- | --- | --- |\n");
        for (String key : List.of("屏幕", "芯片", "电池", "有线充电", "无线充电", "快充协议", "扬声器", "销售规格")) {
            table.append("| ").append(key).append(" | ").append(left.value(key))
                    .append(" | ").append(right.value(key)).append(" |\n");
        }
        return table + "\n" + left.source() + "\n" + right.source();
    }

    String compatibility(String phoneName, String accessoryName) {
        Entry phone = require("phone", phoneName);
        Entry accessory = require("accessory", accessoryName);
        var evidence = new StringBuilder();
        for (JsonNode item : accessory.details().path("compatibility")) {
            if (item.path("phoneId").asText().equals(phone.id())) {
                evidence.append("具体机型证据：").append(item.path("statement").asText()).append('\n');
            } else if (item.path("phoneSeries").asText().equals(phone.series())) {
                evidence.append("系列级证据：").append(item.path("statement").asText())
                        .append("。具体机型的协商功率未确认。\n");
            }
        }
        if (evidence.isEmpty()) {
            evidence.append("此配件与该手机的实际快充功率缺少可核实的配对数据。\n");
        }
        return phone.name() + " / " + accessory.name() + "\n" + evidence
                + "手机侧协议：" + phone.value("快充协议") + "。端口和配件标称："
                + accessory.value("输出") + "。\n" + phone.source() + "\n" + accessory.source();
    }

    String serviceCenters(String city) {
        List<Entry> found = entries("service_center").stream()
                .filter(entry -> normalize(entry.series()).replaceFirst("市$", "")
                        .equals(normalize(city).replaceFirst("市$", ""))).toList();
        String official = "[小米授权服务网点](https://www.mi.com/service/sitelist)";
        if (found.isEmpty()) {
            return "当前目录没有该城市的已采集网点。可查：" + official;
        }
        String snapshot = String.join("\n\n", found.stream().limit(20).map(Entry::describe).toList());
        return "公开目录快照（共 " + found.size() + " 条，最多展示 20 条）\n" + snapshot + "\n" + official;
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_-]+", "");
    }

    record Entry(String id, String kind, String name, String series, String sourceUrl, String checkedOn,
                 JsonNode details) {
        boolean matches(String key) {
            if (normalize(id).equals(key) || normalize(name).equals(key)) {
                return true;
            }
            for (JsonNode alias : details.path("aliases")) {
                if (normalize(alias.asText()).equals(key)) {
                    return true;
                }
            }
            return false;
        }

        String value(String key) {
            return details.path("fields").path(key).asText("资料未记录").replace("|", "｜")
                    .replaceAll("[\\r\\n]+", " ");
        }

        String source() {
            return "[" + name + "的公开来源](" + sourceUrl + ")（核对日期 " + checkedOn + "）";
        }

        String describe() {
            var result = new StringBuilder(name).append("（标识 ").append(id).append("）\n");
            details.path("fields").fields().forEachRemaining(field ->
                    result.append("- ").append(field.getKey()).append("：").append(field.getValue().asText())
                            .append('\n'));
            return result.append(source()).toString();
        }
    }
}
