// Modified for LabDesk.
import * as React from "react";
import { BookOpen, Braces, History, Plus } from "lucide-react";
import { Link } from "react-router-dom";

import { AgentRawLog } from "@/components/agent/AgentRawLog";
import { AgentSidebar } from "@/components/agent/AgentSidebar";
import { getAgentMeta } from "@/services/agentService";
import type { AgentEngineMeta } from "@/types/agent";

export type AgentMetaState =
  | { status: "probing" }
  | { status: "online"; meta: AgentEngineMeta }
  | { status: "offline"; message: string };

// 进页拉一次 /agent/v1/meta 点亮徽标与框架信息块
function useAgentMeta(): AgentMetaState {
  const [state, setState] = React.useState<AgentMetaState>({ status: "probing" });

  React.useEffect(() => {
    let alive = true;
    getAgentMeta()
      .then((meta) => {
        if (alive) setState({ status: "online", meta });
      })
      .catch((error) => {
        if (alive) {
          setState({ status: "offline", message: (error as Error).message || "连接失败" });
        }
      });
    return () => {
      alive = false;
    };
  }, []);

  return state;
}

interface AgentHeaderProps {
  meta: AgentMetaState;
  rawOpen: boolean;
  onToggleRaw: () => void;
  railOpen: boolean;
  railToggleRef: React.Ref<HTMLButtonElement>;
  headerActionsRef: React.Ref<HTMLDivElement>;
  onToggleRail: () => void;
  onNewChat: () => void;
}

function AgentHeader({ meta, rawOpen, onToggleRaw, railOpen, railToggleRef, headerActionsRef, onToggleRail, onNewChat }: AgentHeaderProps) {
  const badgeName =
    meta.status === "online" ? "已连接" : meta.status === "probing" ? "连接中" : "离线";
  const connectionDetails = meta.status === "online"
    ? [meta.meta.framework, meta.meta.model].filter(Boolean).join(" · ")
    : meta.status === "offline" ? meta.message : undefined;

  return (
    <header className="agent-header">
      <div className="agent-brand">
        <img className="lab-brand-mark" src="/favicon.svg" alt="" />
        <span className="agent-wordmark">数码商城<span className="lab-wordmark-suffix">智能体</span></span>
      </div>

      <div className="agent-header-center">
        <span className="agent-badge" title={connectionDetails}>
          <span className="agent-dot" data-status={meta.status} aria-hidden="true" />
          <span className="agent-badge-name">{badgeName}</span>
        </span>
      </div>

      <div className="agent-header-right">
        <button
          type="button"
          ref={railToggleRef}
          className="agent-head-btn lab-rail-toggle"
          aria-label="会话历史"
          aria-expanded={railOpen}
          aria-controls="lab-workbench-sidebar"
          data-on={railOpen}
          onClick={onToggleRail}
        >
          <History size={17} aria-hidden="true" /> <span>会话历史</span>
        </button>
        <div className="lab-header-tools" ref={headerActionsRef}>
          <Link to="/knowledge" className="agent-head-btn lab-knowledge-link" aria-label="资料问答">
            <BookOpen size={17} aria-hidden="true" /> <span>资料问答</span>
          </Link>
          <button
            type="button"
            className="agent-head-btn"
            data-on={rawOpen}
            onClick={onToggleRaw}
            aria-pressed={rawOpen}
            aria-label="原始帧"
          >
            <Braces size={17} aria-hidden="true" /> <span>原始帧</span>
          </button>
          <button type="button" className="agent-head-btn lab-new-chat" aria-label="新建会话" onClick={onNewChat}>
            <Plus size={17} aria-hidden="true" /> <span>新建会话</span>
          </button>
        </div>
      </div>
    </header>
  );
}

interface AgentLayoutProps {
  children: React.ReactNode;
  welcome: boolean;
  onNewChat: () => void;
}

export function AgentLayout({ children, welcome, onNewChat }: AgentLayoutProps) {
  const [rawOpen, setRawOpen] = React.useState(false);
  const [drawerOpen, setDrawerOpen] = React.useState(false);
  const railToggleRef = React.useRef<HTMLButtonElement>(null);
  const sidebarRef = React.useRef<HTMLElement>(null);
  const searchRef = React.useRef<HTMLInputElement>(null);
  const focusSearchOnOpen = React.useRef(false);
  const headerActionsRef = React.useRef<HTMLDivElement>(null);
  const mainRef = React.useRef<HTMLElement>(null);
  const meta = useAgentMeta();

  React.useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || !(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "k") return;
      event.preventDefault();
      if (drawerOpen) {
        searchRef.current?.focus();
        return;
      }
      focusSearchOnOpen.current = true;
      setRawOpen(false);
      setDrawerOpen(true);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [drawerOpen]);

  React.useLayoutEffect(() => {
    if (mainRef.current) mainRef.current.inert = drawerOpen;
    if (headerActionsRef.current) headerActionsRef.current.inert = drawerOpen;
    if (!drawerOpen) return;
    const target = focusSearchOnOpen.current
      ? searchRef.current
      : sidebarRef.current?.querySelector<HTMLElement>(".agent-new-btn");
    focusSearchOnOpen.current = false;
    target?.focus();
  }, [drawerOpen]);

  const closeRail = () => {
    setDrawerOpen(false);
    if (drawerOpen) railToggleRef.current?.focus();
  };

  const handleDrawerKeyDown = (event: React.KeyboardEvent) => {
    if (!drawerOpen || event.defaultPrevented) return;
    if (event.key === "Escape") {
      event.preventDefault();
      closeRail();
      return;
    }
    if (event.key !== "Tab" || !sidebarRef.current || !railToggleRef.current) return;

    const sidebarStops = Array.from(sidebarRef.current.querySelectorAll<HTMLElement>(
      "a[href], button, input, select, textarea, [tabindex]"
    )).filter((element) => element.tabIndex >= 0 && !element.matches(":disabled")
      && element.getClientRects().length > 0 && getComputedStyle(element).visibility === "visible");
    const first = railToggleRef.current;
    const last = sidebarStops[sidebarStops.length - 1] ?? first;
    const boundary = event.shiftKey ? first : last;
    if (event.target === boundary) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    }
  };

  return (
    <div
      className="agent-app"
      data-rail-open={drawerOpen}
      onKeyDown={handleDrawerKeyDown}
    >
      <AgentHeader
        meta={meta}
        rawOpen={rawOpen}
        onToggleRaw={() => setRawOpen((open) => !open)}
        railOpen={drawerOpen}
        railToggleRef={railToggleRef}
        headerActionsRef={headerActionsRef}
        onToggleRail={() => {
          if (drawerOpen) {
            closeRail();
          } else {
            setRawOpen(false);
            setDrawerOpen(true);
          }
        }}
        onNewChat={onNewChat}
      />
      <div className="agent-body">
        {drawerOpen ? (
          <button
            type="button"
            className="lab-rail-backdrop"
            tabIndex={-1}
            aria-label="关闭会话历史"
            onClick={closeRail}
          />
        ) : null}
        <AgentSidebar onNavigate={closeRail} onNewChat={onNewChat} sidebarRef={sidebarRef} searchRef={searchRef} />
        <main className="agent-main" ref={mainRef} data-welcome={welcome}>{children}</main>
        {rawOpen ? <AgentRawLog onClose={() => setRawOpen(false)} /> : null}
      </div>
    </div>
  );
}
