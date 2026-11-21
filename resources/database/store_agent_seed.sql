-- Store tools and Skills. Re-running the provisioner preserves edits in the admin UI.
INSERT INTO t_intent_node (id, intent_code, name, level, description, mcp_tool_id, require_confirm, kind, sort_order, enabled) VALUES
    ('2001523723396310101', 'store_date', '今天日期', 0, '北京时间日期', 'current_date', 0, 2, 10, 1),
    ('2001523723396310102', 'store_product', '商品规格', 0, '查询手机或充电配件公开规格', 'store_product', 0, 2, 20, 1),
    ('2001523723396310103', 'store_compare', '机型规格对比', 0, '并排比较两款手机', 'store_compare', 0, 2, 30, 1),
    ('2001523723396310104', 'store_compatibility', '充电配件兼容', 0, '查找公开的机型、系列、协议证据', 'store_compatibility', 0, 2, 40, 1),
    ('2001523723396310105', 'store_policy', '促销与售后规则', 0, '查阅优惠券、价保和售后规则', 'store_policy', 0, 2, 50, 1),
    ('2001523723396310106', 'store_service_centers', '官方授权维修网点', 0, '查询可核实的官方网点', 'store_service_centers', 0, 2, 60, 1),
    ('2001523723396310107', 'store_demo_slots', '查询演示维修时段', 0, '按机型和城市查询可预约的演示号源', 'store_demo_slots', 0, 2, 70, 1),
    ('2001523723396310108', 'store_create_demo_booking', '创建演示维修预约', 0, '确认后为当前用户创建演示预约', 'store_create_demo_booking', 1, 2, 80, 1),
    ('2001523723396310109', 'store_my_demo_bookings', '我的演示预约', 0, '查询当前账号的演示预约', 'store_my_demo_bookings', 0, 2, 90, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_sample_question (id, title, description, question) VALUES
    ('2001523723396310201', '商品咨询', '查阅官方规格', 'REDMI K90 的有线充电功率和支持的快充协议有哪些？'),
    ('2001523723396310202', '规格比对', '区分同系列机型', '对比 REDMI K90 和 REDMI K90 Pro Max，列出可核实的规格并附出处。'),
    ('2001523723396310203', '促销政策', '解释优惠适用范围', '小米商城的优惠券能叠加吗？遇到活动单独规定应该看哪里？'),
    ('2001523723396310204', '售后服务', '排查故障并查询预约', '我用 REDMI K90，在哈尔滨，换了线还是充不上电，可以约明天的演示维修吗？')
ON CONFLICT (id) DO NOTHING;

INSERT INTO t_agent_skill (id, skill_code, name, description, content, tool_ids, sort_order) VALUES
    ('2001523723396310301', 'phone_troubleshooting', '手机故障排查', '用户反馈充电、开机等故障时加载。', '先确认具体机型、表现和已经尝试的步骤。用 search_knowledge 查找官方依据，仅给出相符的低风险步骤。无法解决时查询官方网点。', '["store_service_centers"]'::jsonb, 10),
    ('2001523723396310302', 'demo_repair', '演示维修预约', '用户明确要求演示预约、查号源或查自己的预约时加载。', '先确认城市、具体机型和故障。遇到相对日期先查询当前日期。查询演示号源后让用户选择网点和时间。用户选定后用服务返回的编号和时间调用 store_create_demo_booking，系统会生成带按钮的待确认卡。等待用户点卡片，收到成功结果后显示演示编号。', '["store_demo_slots","store_create_demo_booking","store_my_demo_bookings"]'::jsonb, 20)
ON CONFLICT (id) DO NOTHING;

-- Upgrade the original shipped defaults while retaining edited prompts and Skills.
UPDATE t_agent_skill
SET content = '先确认城市、具体机型和故障。遇到相对日期先查询当前日期。查询演示号源后让用户选择网点和时间。用户选定后用服务返回的编号和时间调用 store_create_demo_booking，系统会生成带按钮的待确认卡。等待用户点卡片，收到成功结果后显示演示编号。',
    update_time = CURRENT_TIMESTAMP
WHERE id = '2001523723396310302'
  AND content = '先确认城市、具体机型和故障。遇到相对日期先查询当前日期。查询演示号源后让用户选择网点和时间。只传服务返回的网点编号与时段，等待系统确认卡的用户决定。成功后显示演示编号。';

UPDATE t_agent_prompt
SET content = replace(
        replace(content,
            '保留来源链接和证据的适用粒度。',
            '保留来源链接和证据的适用粒度。只有系列级区间或协议交集时，说明可能兼容，并注明具体组合的快充能力和功率仍待确认。'),
        '只有用户明确要求办理并选定时段后才调用 store_create_demo_booking。必须等待系统确认，收到成功结果才显示演示预约编号。真实办理提供官方入口。',
        '用户明确要求演示预约并选定时段后，调用 store_create_demo_booking 以触发网页的系统待确认卡。点击确认按钮前后台不会创建预约。由系统卡完成确认，收到工具成功结果后才显示演示编号。真实办理提供官方入口。'),
    update_time = CURRENT_TIMESTAMP
WHERE id = '2001523723396309017'
  AND content LIKE '你是数码商城智能体，参考中国大陆小米商城公开资料。用户在使用独立演示系统。%'
  AND content LIKE '%只有用户明确要求办理并选定时段后才调用 store_create_demo_booking。必须等待系统确认，收到成功结果才显示演示预约编号。真实办理提供官方入口。%';
