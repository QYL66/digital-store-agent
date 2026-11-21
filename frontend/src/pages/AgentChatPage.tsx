import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { AgentChatInput } from "@/components/agent/AgentChatInput";
import { AgentLayout } from "@/components/agent/AgentLayout";
import { AgentMessageList } from "@/components/agent/AgentMessageList";
import { AgentWelcomeActions } from "@/components/agent/AgentWelcomeActions";
import { useAgentChatStore } from "@/stores/agentChatStore";

export function AgentChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    messages,
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    loadSessions,
    loadMessages,
    startNewChat,
    setDraft
  } = useAgentChatStore();
  const [sessionsReady, setSessionsReady] = React.useState(false);
  const selectedRoute = React.useRef<{ sessionId: string | undefined } | null>(null);
  const sessionExists = React.useMemo(() => {
    if (!sessionId) return false;
    return sessions.some((session) => session.id === sessionId);
  }, [sessionId, sessions]);

  React.useEffect(() => {
    let active = true;
    loadSessions()
      .catch(() => null)
      .finally(() => {
        if (active) {
          setSessionsReady(true);
        }
      });
    return () => {
      active = false;
    };
  }, [loadSessions]);

  React.useEffect(() => {
    const routeChanged =
      selectedRoute.current === null || selectedRoute.current.sessionId !== sessionId;
    selectedRoute.current = { sessionId };

    if (sessionId) {
      if (sessionsReady && !sessionExists) {
        startNewChat();
        navigate("/chat", { replace: true });
        return;
      }
      // 新建会话会先清空 store，再切换网址；只在路由变化时选中历史，
      // 避免旧网址在这两步之间把刚清空的会话恢复回来。
      if (routeChanged) {
        loadMessages(sessionId).catch(() => null);
      }
      return;
    }

    if (routeChanged) {
      startNewChat();
      return;
    }

    // 留在草稿页时，首条消息的 meta 才负责把新 conversationId 同步到网址。
    if (currentSessionId) {
      navigate(`/chat/${currentSessionId}`, { replace: true });
    }
  }, [
    sessionId,
    sessionsReady,
    sessionExists,
    currentSessionId,
    loadMessages,
    startNewChat,
    navigate
  ]);

  const showWelcome = messages.length === 0 && !isLoading;

  return (
    <AgentLayout
      welcome={showWelcome}
      onNewChat={() => {
        startNewChat();
        setDraft("");
        navigate("/chat");
      }}
    >
      {showWelcome ? (
        <header className="lab-workbench-intro">
          <h1 className="lab-title">想了解哪款手机？</h1>
          <p className="lab-lede">查询 Xiaomi、REDMI 手机与充电配件的公开资料。</p>
        </header>
      ) : (
        <AgentMessageList
          messages={messages}
          isLoading={isLoading}
          isStreaming={isStreaming}
          sessionKey={currentSessionId}
        />
      )}
      <AgentChatInput />
      {showWelcome ? <AgentWelcomeActions /> : null}
    </AgentLayout>
  );
}
