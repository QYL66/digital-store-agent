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

package io.github.qyl66.labdesk.knowledge.schedule;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import io.github.qyl66.labdesk.knowledge.config.KnowledgeScheduleProperties;
import io.github.qyl66.labdesk.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import io.github.qyl66.labdesk.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import io.github.qyl66.labdesk.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import io.github.qyl66.labdesk.knowledge.enums.ScheduleRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "STORE_AGENT_LIVE_SCHEDULE_TEST", matches = "true")
class StoreScheduleLeaseDatabaseTest {

    @Test
    void onlyCurrentUnexpiredOwnerCanRenewAndWrite() throws Exception {
        String password = System.getenv("STORE_AGENT_PG_PASSWORD");
        assertThat(password).as("password from the isolated Store Agent database").isNotBlank();
        var dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:25432/store_agent", "postgres", password);
        var jdbc = new JdbcTemplate(dataSource);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(KnowledgeDocumentScheduleMapper.class);
        configuration.addMapper(KnowledgeDocumentScheduleExecMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(dataSource);
        var session = new SqlSessionTemplate(factory.getObject());
        var mapper = session.getMapper(KnowledgeDocumentScheduleMapper.class);
        var state = new ScheduleStateManager(mapper, session.getMapper(KnowledgeDocumentScheduleExecMapper.class));
        var properties = new KnowledgeScheduleProperties();
        properties.setLockSeconds(60L);
        var left = new ScheduleLockManager(mapper, properties);
        var right = new ScheduleLockManager(mapper, properties);
        var workers = Executors.newFixedThreadPool(2);
        String id = UUID.randomUUID().toString().substring(0, 20);
        jdbc.update("INSERT INTO t_knowledge_document_schedule(id, doc_id, kb_id, enabled) VALUES (?, ?, 'qa', 0)", id, id);

        try {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var leftFuture = workers.submit(claimAtSameTime(left, id, ready, start));
            var rightFuture = workers.submit(claimAtSameTime(right, id, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ScheduleLockLease a = leftFuture.get(5, TimeUnit.SECONDS);
            ScheduleLockLease b = rightFuture.get(5, TimeUnit.SECONDS);
            assertThat(a == null ^ b == null).as("only one instance acquires the lease").isTrue();
            var holder = a != null ? left : right;
            var other = a != null ? right : left;
            var lease = a != null ? a : b;
            assertThat(mapper.selectById(id).getLockOwner()).isEqualTo(lease.lockToken());

            Date firstExpiry = mapper.selectById(id).getLockUntil();
            try (var heartbeat = holder.startHeartbeat(lease)) {
                Date renewed = waitForRenewal(mapper, id, firstExpiry);
                assertThat(renewed).isAfter(firstExpiry);
                assertThat(heartbeat.isLost()).isFalse();
                assertThat(other.tryAcquire(id, new Date())).isNull();
            }

            jdbc.update("UPDATE t_knowledge_document_schedule SET lock_until = ? WHERE id = ?",
                    new Timestamp(System.currentTimeMillis() - 1_000), id);
            var refresh = ScheduleStateContext.builder()
                    .scheduleId(id).startTime(new Date()).nextRunTime(new Date()).build();
            assertThat(holder.renew(lease)).isFalse();
            assertThat(state.markSkippedIfOwned(lease, refresh, "expired owner")).isFalse();
            assertThat(mapper.selectById(id).getLastStatus()).isNull();

            var replacement = other.tryAcquire(id, new Date());
            assertThat(replacement).isNotNull();
            assertThat(replacement.lockToken()).isNotEqualTo(lease.lockToken());
            assertThat(holder.release(lease)).isFalse();
            assertThat(state.markSkippedIfOwned(lease, refresh, "replaced owner")).isFalse();
            assertThat(state.markSkippedIfOwned(replacement, refresh, "current owner")).isTrue();
            KnowledgeDocumentScheduleDO saved = mapper.selectById(id);
            assertThat(saved.getLastStatus()).isEqualTo(ScheduleRunStatus.SKIPPED.getCode());
            assertThat(saved.getLastError()).isEqualTo("current owner");
            assertThat(other.release(replacement)).isTrue();
        } finally {
            workers.shutdownNow();
            left.shutdown();
            right.shutdown();
            jdbc.update("DELETE FROM t_knowledge_document_schedule WHERE id = ? AND enabled = 0", id);
        }
    }

    private static Callable<ScheduleLockLease> claimAtSameTime(
            ScheduleLockManager node, String id, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("lease contender did not start");
            }
            return node.tryAcquire(id, new Date());
        };
    }

    private static Date waitForRenewal(KnowledgeDocumentScheduleMapper mapper, String id, Date firstExpiry)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(28).toNanos();
        Date expiry;
        do {
            expiry = mapper.selectById(id).getLockUntil();
            if (expiry.after(firstExpiry)) {
                return expiry;
            }
            Thread.sleep(300);
        } while (System.nanoTime() < deadline);
        return expiry;
    }
}
