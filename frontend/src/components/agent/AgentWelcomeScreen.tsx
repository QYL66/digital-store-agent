import * as React from "react";
import { Columns2, MessageCircle, Search, TicketPercent, Wrench, type LucideIcon } from "lucide-react";

import { listSampleQuestions, type SampleQuestion } from "@/services/sampleQuestionService";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";

/** 待机页取几条 后台配得少就少显示 不凑数 */
const SAMPLE_LIMIT = 4;
const SAMPLE_SHORTCUTS = new Map<string, { icon: LucideIcon; order: number }>([
  ["商品咨询", { icon: Search, order: 0 }],
  ["规格比对", { icon: Columns2, order: 1 }],
  ["促销政策", { icon: TicketPercent, order: 2 }],
  ["售后咨询", { icon: Wrench, order: 3 }]
]);

function shortcutOrder(title?: string | null) {
  return SAMPLE_SHORTCUTS.get(title?.trim() ?? "")?.order ?? SAMPLE_LIMIT;
}

type SampleState =
  | { status: "loading" }
  | { status: "ready"; items: SampleQuestion[] }
  | { status: "error" };

/** 取过一次就记在模块里：换会话回到待机页不该重新洗牌 */
let cachedQuestions: SampleQuestion[] | null = null;

function useSampleQuestions() {
  const [state, setState] = React.useState<SampleState>(() =>
    cachedQuestions ? { status: "ready", items: cachedQuestions } : { status: "loading" }
  );
  const aliveRef = React.useRef(true);

  const load = React.useCallback(() => {
    setState({ status: "loading" });
    listSampleQuestions(SAMPLE_LIMIT)
      .then((rows) => {
        const items = (rows ?? [])
          .filter((row) => row.question?.trim())
          .sort((first, second) => shortcutOrder(first.title) - shortcutOrder(second.title));
        cachedQuestions = items;
        if (aliveRef.current) setState({ status: "ready", items });
      })
      .catch(() => {
        if (aliveRef.current) setState({ status: "error" });
      });
  }, []);

  React.useEffect(() => {
    aliveRef.current = true;
    if (!cachedQuestions) load();
    return () => {
      aliveRef.current = false;
    };
  }, [load]);

  return { state, reload: load };
}

/** 示例问题：来自后台配置 点一条只填进输入框 发不发由用户决定 */
function AgentSampleQuestions() {
  const setDraft = useAgentChatStore((store) => store.setDraft);
  const isAdmin = useAuthStore((store) => store.user?.role === "admin");
  const { state, reload } = useSampleQuestions();

  // 加载中不占位也不放骨架屏：请求就在同一屏内 闪一下比空框好
  if (state.status === "loading") {
    return null;
  }

  const questions = state.status === "ready" ? state.items : [];
  if (!questions.length) {
    return (
      <div className="agent-empty-blank">
        <p>{state.status === "error" ? "示例问题暂时无法加载。你可以直接提问。" : "在下面输入你想查询的问题。"}</p>
        {state.status === "error" ? (
          <button type="button" className="agent-empty-blank-btn" onClick={reload}>重试</button>
        ) : isAdmin ? (
          <a className="agent-empty-blank-link" href="/admin/sample-questions" target="_blank" rel="noreferrer">
            配置示例问题
          </a>
        ) : null}
      </div>
    );
  }

  return (
    <div className="agent-empty-chips" role="group" aria-label="商城快捷查询">
      {questions.map(({ id, title, question, description }) => {
        const label = title?.trim() || question;
        const Icon = SAMPLE_SHORTCUTS.get(label)?.icon ?? MessageCircle;
        const subtitle = description?.trim();
        return (
          <button
            key={id}
            type="button"
            className="agent-empty-chip"
            title={question}
            onClick={() => setDraft(question)}
          >
            <span className="agent-empty-chip-icon">
              <Icon size={18} strokeWidth={1.7} aria-hidden="true" />
            </span>
            <span className="agent-empty-chip-text">
              <strong>{label}</strong>
              {subtitle ? <small>{subtitle}</small> : null}
            </span>
          </button>
        );
      })}
    </div>
  );
}

export function AgentWelcomeScreen() {
  return (
    <div className="agent-stream-empty">
      <div className="agent-empty-wrap">
        <div className="agent-empty-card">
          <h1>想查哪款手机？</h1>
          <p className="agent-empty-description">查小米与 REDMI 商品、充电配件、优惠规则和售后资料。</p>
          <AgentSampleQuestions />
        </div>
      </div>
    </div>
  );
}
