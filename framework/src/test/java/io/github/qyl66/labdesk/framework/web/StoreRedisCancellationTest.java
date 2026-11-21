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

package io.github.qyl66.labdesk.framework.web;

import io.github.qyl66.labdesk.framework.context.LoginUser;
import io.github.qyl66.labdesk.framework.context.UserContext;
import io.github.qyl66.labdesk.framework.exception.ClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "STORE_AGENT_LIVE_REDIS_TEST", matches = "true")
class StoreRedisCancellationTest {

    @Test
    void redisDeliversStopFromAnotherClientAndSettlesOnce() throws Exception {
        RedissonClient first = connect();
        RedissonClient second = connect();
        StreamTaskManager owner = new StreamTaskManager(first);
        StreamTaskManager receiver = new StreamTaskManager(second);
        String taskId = UUID.randomUUID().toString();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger finalizations = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(1);
        try {
            owner.subscribe();
            receiver.subscribe();
            owner.register(taskId, "test-owner", () -> {
                finalizations.incrementAndGet();
                finished.countDown();
            });
            owner.bindHandle(taskId, cancellations::incrementAndGet);
            UserContext.set(LoginUser.builder().userId("test-other").build());
            assertThatThrownBy(() -> receiver.cancelByUser(taskId)).isInstanceOf(ClientException.class);
            assertThat(finished.await(200, TimeUnit.MILLISECONDS)).isFalse();
            UserContext.set(LoginUser.builder().userId("test-owner").build());
            receiver.cancelByUser(taskId);
            receiver.cancelByUser(taskId);
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellations).hasValue(1);
            assertThat(finalizations).hasValue(1);
        } finally {
            UserContext.clear();
            owner.unregister(taskId);
            owner.unsubscribe();
            receiver.unsubscribe();
            first.shutdown();
            second.shutdown();
        }
    }

    private RedissonClient connect() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:26379")
                .setPassword(System.getenv("STORE_AGENT_REDIS_PASSWORD"));
        return Redisson.create(config);
    }
}
