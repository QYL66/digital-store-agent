"""Freeze the public-catalogue corpus and 50 manually specified test cases."""

from collections import Counter
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CATALOGUE = ROOT / "resources/store-catalog/records.json"
DESTINATION = Path(__file__).resolve().parent
RECORDS = {item["id"]: item for item in json.loads(CATALOGUE.read_text(encoding="utf-8-sig"))}
CASES = []


def case(prefix, number, category, query, ids, facts, tools, *, retrieval=False, risk=False,
         follow_up=None, slot=None):
    turns = [{"turn_index": 1, "user_query": query,
              "expected_behavior": "ask_clarification" if follow_up else "answer_with_citation",
              "expected_clarification_slot": slot, "expected_mcp_tools": [] if follow_up else tools}]
    if follow_up:
        turns.append({"turn_index": 2, "user_query": follow_up, "expected_behavior": "answer_with_citation",
                      "expected_clarification_slot": None, "expected_mcp_tools": tools})
    CASES.append({
        "case_id": f"{prefix}-{number:02d}", "category": category, "in_rag_ablation": retrieval,
        "power_misattribution_risk": risk, "turns": turns,
        "retrieval_ground_truth": {
            "gold_doc_ids": ids, "gold_chunk_ids": [key + "#01" for key in ids],
        },
        "answer_ground_truth": {
            "must_include_facts": facts,
            "forbidden_claims": (["将配件标称最大输出或系列范围承诺为具体机型实际输入功率"] if risk else []),
            "required_citation_sources": list(dict.fromkeys(RECORDS[key]["source_url"] for key in ids)),
        },
    })


def fields(key, *labels):
    return [f"{RECORDS[key]['name']} {label}为{RECORDS[key]['details']['fields'][label]}"
            for label in labels]


specs = [
    ("redmi-k90", "红米K九零大概多少毫安时？盒里头子的瓦数也说下", ("电池", "有线充电")),
    ("redmi-k90-pro-max", "k90promax声音有独立低音吗，屏多大？", ("扬声器", "屏幕")),
    ("xiaomi-17", "米17普通款电池是不是七千？无线充能标多少？", ("电池", "无线充电")),
    ("xiaomi-17-pro", "小米17pro后面那块小屏多大，芯片啥型号？", ("屏幕", "芯片")),
    ("xiaomi-17-ultra", "17U不是Pro，电池多大，线充标几W？", ("电池", "有线充电")),
    ("redmi-k80-pro", "k80pro原装线充头子标几瓦，手机电池多大", ("有线充电", "电池")),
    ("redmi-note-17", "note17普通版，电池和有线充大概啥水平？", ("电池", "有线充电")),
    ("redmi-turbo-5-max", "红米turbo5max是什么U？电池接近一万吗？", ("芯片", "电池")),
    ("xiaomi-15", "米15小屏版的屏幕大小和最高刷新率咋样？", ("屏幕",)),
    ("xiaomi-mix-flip-2", "MIX Flip二代翻盖里的屏多少寸？外屏呢？", ("屏幕",)),
]
for n, (key, query, labels) in enumerate(specs, 1):
    case("SP", n, "spec_query", query, [key], fields(key, *labels), ["store_product"], retrieval=n <= 8)


comparisons = [
    ("redmi-k90", "redmi-k90-pro-max", "K90跟K90pm，谁电池大，分别多少？", "电池"),
    ("xiaomi-17", "xiaomi-17-pro", "米17普版和17pro，续航电池谁多？说典型容量", "电池"),
    ("xiaomi-17-pro", "xiaomi-17-ultra", "17Pro改上17U，插线标称瓦数差多少？", "有线充电"),
    ("redmi-k80", "redmi-k80-pro", "同叫K80，带pro和不带pro的用啥处理器？", "芯片"),
    ("xiaomi-15", "xiaomi-15-pro", "就比一下面积，米15普通和pro分别几英寸？", "屏幕"),
    ("redmi-turbo-5", "redmi-turbo-5-max", "turbo5还是max电池大？俩数都报下", "电池"),
    ("redmi-note-17", "redmi-note-17-pro", "Note17和Note17pro的头子的瓦数差多少？", "有线充电"),
    ("redmi-k80", "redmi-k90", "K80普通款和下一代K90，电池差了多少？", "电池"),
    ("xiaomi-15", "xiaomi-17", "米15和米17的无线充标称瓦数有没有变化？", "无线充电"),
    ("xiaomi-17-ultra", "redmi-k90-pro-max", "17U和K90顶配，典型电池各是多少？", "电池"),
]
for n, (first, second, query, label) in enumerate(comparisons, 1):
    case("CP", n, "model_compare", query, [first, second],
         fields(first, label) + fields(second, label), ["store_compare"], retrieval=n <= 8)


charging = [
    ("redmi-k90", "pb2165", "K90插pb2165自带线，店里写165w是我手机也能满速吗？"),
    ("redmi-k90-pro-max", "pb2165", "k90pm接165W两万毫安那块砖，能保证120W不？"),
    ("xiaomi-17", "pb2165", "米17配那块PB2165，带的C线说120瓦，手机实际也是120吗？"),
    ("redmi-note-17", "pb2165", "红米note17普版拿20000 165W充电宝，120瓦能直接充吗"),
    ("redmi-k80", "165w-10000-integrated", "万毫安自带线165w那款，给老K80插上就必到165W？"),
    ("xiaomi-15", "plm11zm", "米15放PLM11ZM上标的10w就是实测能一直到的值吗？"),
    ("xiaomi-17-pro", "mi-50w-air-wireless-pro", "17pro放小米50W风冷无线充Pro上，店里的50W能当手机实际功率么？"),
    ("redmi-turbo-5", "mi-100w-car-1a1c", "车上1A1C写100W，红米turbo5插C口就一直有100瓦？"),
    ("xiaomi-17-ultra", "mi-30w-magnet", "17U搭30w小米磁吸充，能直接承诺30w吗？"),
    ("redmi-k80-pro", "mi-90w-charger-kit", "K80pro原来标120W，拿小米90W套装能稳定到90吗？"),
    ("redmi-k90", "mi-20000-67w", "红米K90用两万自带线67W的宝，一插就是67W？"),
    ("xiaomi-15-pro", "mi-20000-225w", "米15pro用小米自带线20000 22.5W，实测一定22.5w吗？"),
]


def charge_facts(phone, accessory):
    return fields(accessory, "输出") + [
        f"区分{RECORDS[phone]['name']}机型的公开兼容证据和配件标称规格",
        "仅有系列或商品级证据时，具体搭配实际功率待核实",
    ]


for n, (phone, accessory, query) in enumerate(charging, 1):
    case("FC", n, "fast_charge_compat", query, [phone, accessory], charge_facts(phone, accessory),
         ["store_compatibility"], retrieval=n <= 8, risk=True)


support = [
    ("coupon", "叠一张券再叠另外一张券行不行，最终扣多少钱看哪？", ("叠加", "账户与结算"), "store_policy"),
    ("price-protection", "刚买就降价了，找补差价要在哪儿查这单有没有资格？", ("条件", "结算入口"), "store_policy"),
    ("aftersale", "手机出了问题，商城APP从哪单子走售后，不知道进度去哪看？", ("申请", "进度"), "store_policy"),
    ("quick-repair", "到店所谓一小时修好，谁都能用吗，官方怎么约？", ("快修范围", "官方预约"), "store_policy"),
    ("charging", "换了根线还没反应又发烫，先咋处理？", ("无法充电",), "store_support"),
    ("power-on", "黑屏没亮开不了机，官方排查在哪里，还是不行呢？", ("入口", "适用说明"), "store_support"),
    ("coupon", "券忘领或者过期还能用么，哪些东西能用怎么看？", ("有效期", "使用范围"), "store_policy"),
    ("charging", "能充但越来越慢，换头换线要按什么思路排？", ("充电慢", "适用说明"), "store_support"),
]
for n, (key, query, labels, tool) in enumerate(support, 1):
    case("AS", n, "after_sales", query, [key], fields(key, *labels), [tool], retrieval=n <= 6)


clarifications = [
    ("pb2165", "redmi-k90", "PB2165我的手机能跑120瓦不？", "我是红米K90普通版。", "target_phone_model"),
    ("pb2165", "xiaomi-17-pro", "这块两万165W的我用能满速吗？", "用小米17 Pro，配件是PB2165。", "target_phone_model"),
    ("mi-90w-charger-kit", "redmi-k80", "我拿小米90W套装就能一直充到90吗？", "红米K80普通款。", "target_phone_model"),
    ("plm11zm", "xiaomi-15", "PLM11ZM给我这台能不能一直跑10瓦？", "小米15手机。", "target_phone_model"),
    ("mi-100w-car-1a1c", "xiaomi-17", "米17普通款用个车充，能跑100W吗？", "车充型号是小米1A1C 100W。", "accessory_model"),
    ("mi-30w-magnet", "xiaomi-17-ultra", "17U配手里那个磁吸无线充可以满多少？", "是小米30W磁吸无线充。", "accessory_model"),
    ("mi-50w-air-wireless-pro", "redmi-k90-pro-max", "我这个小米50W无线充能直接满50吗？", "K90 Pro Max，配件是立式风冷无线充Pro。", "target_phone_model"),
    ("165w-10000-integrated", "redmi-note-17", "红米Note17普通版连我那个充电宝能不能满速？", "是自带线10000 165W那款。", "accessory_model"),
    ("mi-20000-67w", "redmi-turbo-5", "20000自带线67瓦的充电宝能给我的手机满67W吗？", "用红米Turbo 5普通版。", "target_phone_model"),
    ("pb2165", "xiaomi-15-pro", "我的米15pro能从这块充电宝吃多少功率？", "充电宝是小米20000 165W PB2165。", "accessory_model"),
]
for n, (accessory, phone, query, follow, slot) in enumerate(clarifications, 1):
    case("CL", n, "multi_turn_clarify", query, [phone, accessory], charge_facts(phone, accessory),
         ["store_compatibility"], risk=True, follow_up=follow, slot=slot)


def chunk(record):
    details = record["details"]
    fields_text = [f"{key}：{value}" for key, value in sorted(details.get("fields", {}).items())]
    compatibility = [f"{rule.get('phoneSeries', rule.get('phoneId', ''))}：{rule['statement']}"
                     for rule in details.get("compatibility", [])]
    text = "\n".join([record["name"], record.get("series", ""), " ".join(details.get("aliases", [])),
                      *fields_text, *compatibility])
    return {"id": record["id"] + "#01", "document_id": record["id"],
            "source_url": record["source_url"], "text": text}


def write_json(name, value):
    (DESTINATION / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    assert Counter(case["category"] for case in CASES) == {
        "spec_query": 10, "model_compare": 10, "fast_charge_compat": 12,
        "after_sales": 8, "multi_turn_clarify": 10,
    }
    assert sum(case["in_rag_ablation"] for case in CASES) == 30
    write_json("eval_dataset_50.json", CASES)
    write_json("corpus_manifest.json", {
        "snapshot_sha256": hashlib.sha256(CATALOGUE.read_bytes()).hexdigest(),
        "chunks": [{"id": row["id"], "document_id": row["document_id"], "source_url": row["source_url"]}
                   for row in map(chunk, RECORDS.values())],
    })
