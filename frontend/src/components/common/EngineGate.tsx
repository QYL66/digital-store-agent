import * as React from "react";
import { Navigate, useParams } from "react-router-dom";

import { Loading } from "@/components/common/Loading";
import { AgentChatPage } from "@/pages/AgentChatPage";
import { ChatPage } from "@/pages/ChatPage";
import { useEngineStore } from "@/stores/engineStore";

export function EngineGate({ knowledgeChat = false }: { knowledgeChat?: boolean }) {
  const { sessionId } = useParams<{ sessionId: string }>();
  const engineType = useEngineStore((state) => state.engineType);
  const initialize = useEngineStore((state) => state.initialize);

  React.useEffect(() => {
    initialize().catch(() => null);
  }, [initialize]);

  if (!engineType) {
    return (
      <div className="flex h-screen items-center justify-center bg-white">
        <Loading />
      </div>
    );
  }

  if (knowledgeChat) {
    return <ChatPage />;
  }
  if (engineType === "agent") {
    return <AgentChatPage />;
  }
  return <Navigate to={sessionId ? `/knowledge-chat/${sessionId}` : "/knowledge-chat"} replace />;
}
