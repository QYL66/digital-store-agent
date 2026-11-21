// Modified for LabDesk.
import * as React from "react";
import { ArrowUpRight, Columns2, Search, TicketPercent, Wrench } from "lucide-react";

import { listSampleQuestions } from "@/services/sampleQuestionService";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";

/** 待机页取几条 后台配得少就少显示 不凑数 */
const SAMPLE_LIMIT = 4;

const storeActions = [
  { label: "商品咨询", icon: Search, prompt: "REDMI K90 支持哪些快充协议？" },
  { label: "规格比对", icon: Columns2, prompt: "对比 REDMI K90 和 REDMI K90 Pro Max，列出规格及出处。" },
  { label: "促销政策", icon: TicketPercent, prompt: "小米商城优惠券能叠加吗？" },
  { label: "售后服务", icon: Wrench, prompt: "手机换过线还是充不上电，可以帮我排查吗？" },
];

type SampleState =
  | { status: "loading" }
  | { status: "ready"; items: string[] }
  | { status: "error" };

/** 取过一次就记在模块里：换会话回到待机页不该重新洗牌 */
let cachedQuestions: string[] | null = null;

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
          .map((row) => row.question?.trim())
          .filter((question): question is string => Boolean(question));
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
  const isBlank = questions.length === 0;

  return (
    <section className="lab-example" aria-label="示例问题">
      <h2>示例问题</h2>
      {isBlank ? (
        <div className="agent-empty-blank">
          {state.status === "error" ? (
            <p>暂时无法加载示例问题。</p>
          ) : (
            <p>
              暂无示例问题
              {isAdmin ? (
                <>
                  ，可在
                  <a
                    className="agent-empty-blank-link"
                    href="/admin/sample-questions"
                    target="_blank"
                    rel="noreferrer"
                  >
                    管理控制台
                  </a>
                  配置。
                </>
              ) : null}
            </p>
          )}
          {state.status === "error" ? (
            <button type="button" className="agent-empty-blank-btn" onClick={reload}>
              重试
            </button>
          ) : null}
        </div>
      ) : (
        <div className="lab-example-list">
          {questions.map((question) => (
            <button
              key={question}
              type="button"
              className="lab-example-button"
              title="点击填入输入框"
              onClick={() => setDraft(question)}
            >
              <span>{question}</span>
              <ArrowUpRight size={15} aria-hidden="true" />
            </button>
          ))}
        </div>
      )}
    </section>
  );
}

export function AgentWelcomeActions() {
  const setDraft = useAgentChatStore((store) => store.setDraft);

  return (
    <div className="lab-workbench-options">
      <nav className="lab-workbench-actions" aria-label="商城常用操作">
        {storeActions.map(({ label, icon: Icon, prompt }) => (
          <button key={label} type="button" className="lab-action" onClick={() => setDraft(prompt)}>
            <Icon size={19} className="lab-action-icon" aria-hidden="true" />
            <span>{label}</span>
          </button>
        ))}
      </nav>
      <AgentSampleQuestions />
    </div>
  );
}
