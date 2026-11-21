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

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

class StoreBooking {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String DEMO = "【本地演示预约】";
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final StoreCatalog catalog;

    StoreBooking(JdbcTemplate jdbc, Clock clock, StoreCatalog catalog) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.catalog = catalog;
    }

    String availability(String name, String city, LocalDate date) {
        StoreCatalog.Entry model = catalog.require("phone", name);
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        if (date.isBefore(today) || date.isAfter(today.plusDays(7))) {
            throw new IllegalArgumentException("请选择今天至未来七天的日期。");
        }
        List<Center> centers = jdbc.query("""
                SELECT code, name FROM store_demo_center
                WHERE (city = ? OR city || '市' = ?) AND ? = ANY(supported_brands) ORDER BY code
                """, (row, number) -> new Center(row.getString(1), row.getString(2)), city, city, brand(model));
        if (centers.isEmpty()) {
            return DEMO + "该城市当前没有支持此机型的演示网点。真实服务请查看 https://www.mi.com/service/sitelist";
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        StringBuilder result = new StringBuilder(DEMO).append(' ').append(model.name()).append(' ')
                .append(date).append(" 北京时间（每段一小时）\n");
        for (Center center : centers) {
            List<OffsetDateTime> occupied = jdbc.query("""
                    SELECT starts_at FROM store_demo_booking
                    WHERE center_code = ? AND starts_at >= ? AND starts_at < ?
                    """, (row, number) -> row.getObject(1, OffsetDateTime.class),
                    center.code(), at(date, 9), at(date, 18));
            List<String> open = IntStream.rangeClosed(9, 17).mapToObj(hour -> at(date, hour))
                    .filter(time -> time.isAfter(now) && occupied.stream().noneMatch(time::isEqual))
                    .map(TIME::format).toList();
            result.append(center.code()).append(' ').append(center.name()).append("：")
                    .append(open.isEmpty() ? "无空闲时段" : String.join("、", open)).append('\n');
        }
        return result.toString();
    }

    String create(String user, String modelName, String centerCode, LocalDate date, LocalTime time, String symptom) {
        if (time.getHour() < 9 || time.getHour() > 17 || time.getMinute() != 0
                || time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException("请选择 09:00 至 17:00 的整点。");
        }
        StoreCatalog.Entry model = catalog.require("phone", modelName);
        String code = centerCode.strip().toUpperCase(Locale.ROOT);
        Center center = jdbc.query("""
                SELECT code, name FROM store_demo_center WHERE code = ? AND ? = ANY(supported_brands)
                """, (row, number) -> new Center(row.getString(1), row.getString(2)), code, brand(model))
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("网点不支持该机型，请重新查询。"));
        OffsetDateTime start = at(date, time.getHour());
        boolean bookable = start.isAfter(OffsetDateTime.now(clock))
                && !date.isAfter(LocalDate.now(clock.withZone(SHANGHAI)).plusDays(7));
        if (bookable) {
            jdbc.update("""
                    INSERT INTO store_demo_booking (center_code, starts_at, model_id, symptom, user_id)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (center_code, starts_at) DO NOTHING
                    """, center.code(), start, model.id(), symptom, user);
        }
        // Read in a separate statement so a committed competing INSERT is visible.
        return jdbc.query("""
                SELECT id, user_id, model_id, symptom FROM store_demo_booking
                WHERE center_code = ? AND starts_at = ?
                """, (row, number) -> new Booking(row.getLong(1), row.getString(2),
                row.getString(3), row.getString(4)), center.code(), start).stream()
                .filter(booking -> booking.user().equals(user) && booking.model().equals(model.id())
                        && booking.symptom().equals(symptom))
                .findFirst().map(booking -> DEMO + " 编号 DS-" + booking.id() + " · " + model.name()
                        + " · " + center.name() + " · " + date + " " + time + "（北京时间）")
                .orElseThrow(() -> new IllegalArgumentException(bookable
                        ? "时段已被占用，请重新查询。"
                        : "请选择尚未开始且在未来七天内的时段。"));
    }

    String mine(String user) {
        List<String> results = jdbc.query("""
                SELECT b.id, b.starts_at, c.name AS center, p.name AS model
                FROM store_demo_booking b JOIN store_demo_center c ON c.code = b.center_code
                JOIN store_catalog p ON p.id = b.model_id
                WHERE b.user_id = ? ORDER BY b.starts_at DESC, b.id DESC LIMIT 30
                """, (row, number) -> "DS-" + row.getLong("id") + " · " + row.getString("model") + " · "
                + row.getString("center") + " · " + row.getObject("starts_at", OffsetDateTime.class)
                .atZoneSameInstant(SHANGHAI).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), user);
        return DEMO + " " + (results.isEmpty() ? "当前没有预约。" : "北京时间\n" + String.join("\n", results));
    }

    private static OffsetDateTime at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(SHANGHAI).toOffsetDateTime();
    }

    private static String brand(StoreCatalog.Entry model) {
        return model.details().path("brand").asText();
    }

    private record Center(String code, String name) {}
    private record Booking(long id, String user, String model, String symptom) {}
}
