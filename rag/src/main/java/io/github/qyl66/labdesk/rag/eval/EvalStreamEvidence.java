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

package io.github.qyl66.labdesk.rag.eval;

import io.github.qyl66.labdesk.framework.convention.RetrievedChunk;
import io.github.qyl66.labdesk.rag.dto.RetrievalContext;

import java.util.List;

/**
 * Evidence emitted only on an authenticated evaluation stream, from the retrieval used for that answer.
 */
public record EvalStreamEvidence(String promptContext, List<RankedChunk> chunks, long retrievalMs) {

    public static EvalStreamEvidence capture(RetrievalContext context, long elapsed) {
        List<RankedChunk> ranked = context.getIntentChunks().values().stream()
                .flatMap(List::stream).map(RankedChunk::from).distinct().toList();
        return new EvalStreamEvidence(context.getKbContext(), ranked, elapsed);
    }

    public static EvalStreamEvidence empty() {
        return new EvalStreamEvidence("", List.of(), 0);
    }

    public record RankedChunk(String id, String docName, Float rerankScore) {
        private static RankedChunk from(RetrievedChunk chunk) {
            return new RankedChunk(chunk.getId(), chunk.getDocName(), chunk.getRerankScore());
        }
    }
}
