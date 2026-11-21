-- Read-only Xiaomi catalogue and starter questions. Preserve admin edits on repeated setup.
INSERT INTO t_intent_node (id, intent_code, name, level, description, mcp_tool_id, require_confirm, kind, sort_order, enabled) VALUES
    ('2001523723396310101', 'store_date', '今天日期', 0, '查询日期', 'current_date', 0, 2, 10, 1),
    ('2001523723396310102', 'store_product', '商品规格', 0, '查手机或充电配件的公开规格', 'store_product', 0, 2, 20, 1),
    ('2001523723396310103', 'store_compare', '机型规格对比', 0, '并排比较两款手机并标注出处', 'store_compare', 0, 2, 30, 1),
    ('2001523723396310104', 'store_compatibility', '充电配件兼容', 0, '根据手机和配件查找快充证据', 'store_compatibility', 0, 2, 40, 1),
    ('2001523723396310105', 'store_policy', '促销与售后规则', 0, '查阅优惠券、价保和售后规则', 'store_policy', 0, 2, 50, 1),
    ('2001523723396310106', 'store_service_centers', '官方授权维修网点', 0, '查找官方授权维修网点', 'store_service_centers', 0, 2, 60, 1),
    ('2001523723396310110', 'store_catalog', '浏览商城商品', 0, '按系列、名称和别名浏览目录快照', 'store_catalog', 0, 2, 70, 1),
    ('2001523723396310111', 'store_support', '故障资料', 0, '查阅手机充电故障的公开排查资料', 'store_support', 0, 2, 80, 1)
ON CONFLICT DO NOTHING;

INSERT INTO t_sample_question (id, title, description, question) VALUES
    ('2001523723396310201', '商品咨询', '查阅官方规格', 'REDMI K90 的有线充电功率和快充协议是什么？'),
    ('2001523723396310202', '规格比对', '区分同系列机型', '对比 REDMI K90 和 REDMI K90 Pro Max 的屏幕、电池和充电规格。'),
    ('2001523723396310203', '促销政策', '解释优惠适用范围', '小米商城的优惠券能叠加吗？'),
    ('2001523723396310204', '售后咨询', '查阅官方排查资料', 'REDMI K90 换了线还是充不上电，可以先排查什么？')
ON CONFLICT (id) DO NOTHING;
