// Run in ego-browser's Node SDK. It reads the public service-directory page through its own selectors.
const spaceName = "Display the Xiaomi product specifications";
const officialPage = "https://www.mi.com/service/sitelist";
const task = await taskSpace(spaceName);
const page = (await task.pages())[0] ?? task.page("p1");
const fs = await import("node:fs/promises");
const { createHash } = await import("node:crypto");
const output = new URL("../local/logs/service-centers-snapshot.json", import.meta.url);
const diagnostics = new URL("../local/logs/service-centers-coverage.json", import.meta.url);
if ((await page.url()) !== officialPage) {
  await page.goto(officialPage, { timeout: 25_000 });
}

const options = (index) => page.evaluate((position) =>
  [...(document.querySelectorAll("select")[position]?.options ?? [])]
    .filter((item) => item.value && item.value !== "-1")
    .map((item) => ({ id: item.value, label: item.text.trim() })), index);
const provinces = await options(0);
const phoneType = (await options(3)).find((item) => item.label === "手机");
if (!provinces.length || !phoneType) {
  throw new Error("The public directory did not render its province and phone selectors.");
}
const select = (position, value) => page.selectOption("select >> nth=" + position, value);
async function readPrevious(path) {
  try {
    return JSON.parse(await fs.readFile(path, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
}

async function replaceJson(path, value) {
  const pending = new URL(path.href + ".pending");
  await fs.writeFile(pending, JSON.stringify(value, null, 2) + "\n");
  await fs.rename(pending, path);
}

const rows = new Map((await readPrevious(output)).map((row) => [row.id, row]));
const cityKey = (province, city) => `${province}/${city ?? ""}`;
const coverage = new Map((await readPrevious(diagnostics)).map((item) => [cityKey(item.province, item.city), item]));
const checkedOn = new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Shanghai" });
for (const province of provinces) {
  try {
    const currentProvince = await page.evaluate(() => document.querySelector("select")?.value);
    if (currentProvince !== province.id) {
      const previousCities = JSON.stringify(await options(1));
      await select(0, province.id);
      await page.waitForFunction(({ value, previous }) => {
        const [provinceSelect, cities] = document.querySelectorAll("select");
        const available = [...(cities?.options ?? [])].filter((item) => item.value && item.value !== "-1")
          .map((item) => ({ id: item.value, label: item.text.trim() }));
        return provinceSelect?.value === value && available.length && JSON.stringify(available) !== previous;
      }, { value: province.id, previous: previousCities }, { timeout: 8000 });
    }
    const cities = await options(1);
    coverage.delete(cityKey(province.label));
    for (const city of cities) {
      const key = cityKey(province.label, city.label);
      if (coverage.get(key)?.state === "browsed") continue;
      try {
        const currentCity = await page.evaluate(() => document.querySelectorAll("select")[1]?.value);
        if (currentCity !== city.id) await select(1, city.id);
        await page.waitForFunction(({ provinceLabel, cityLabel }) => {
          const heading = document.querySelector(".location-info .info-main");
          return heading?.textContent.trim() === `${provinceLabel},${cityLabel}`;
        }, { provinceLabel: province.label, cityLabel: city.label }, { timeout: 6000 });
        // Xiaomi resets the product filter when changing cities. Read it after the city has settled.
        const type = await page.evaluate(() => document.querySelectorAll("select")[3]?.value);
        if (type !== phoneType.id) await select(3, phoneType.id);
        await page.waitForFunction(({ ids, labels, phone }) => {
          const [provinceSelect, citySelect, district, type] = document.querySelectorAll("select");
          const [heading, countText] = document.querySelectorAll(".location-info .info-main");
          const count = Number(countText?.textContent.trim() ?? NaN);
          const list = document.querySelector("ul.js-store");
          const byCity = heading?.textContent.trim().split(",").map((part) => part.trim());
          return provinceSelect?.value === ids[0] && citySelect?.value === ids[1] && type?.value === phone
            && (!district?.value || district.value === "-1")
            && byCity?.[0] === labels[0] && byCity?.[1] === labels[1]
            && Number.isFinite(count) && ((list?.children.length ?? 0) === count);
        }, { ids: [province.id, city.id], labels: [province.label, city.label], phone: phoneType.id },
        { timeout: 6000 });
        const result = await page.evaluate(() => [...document.querySelectorAll("ul.js-store > li")].map((item) => ({
          name: item.querySelector("h3.info-name")?.innerText.trim(),
          address: item.querySelector("p.info-address")?.innerText.trim(),
        })));
        let missing = 0;
        for (const item of result) {
          if (!item.name || !item.address) { missing++; continue; }
          const hash = createHash("sha256").update([province.id, city.id, item.name, item.address].join("\n"))
            .digest("hex").slice(0, 20);
          rows.set("mi-center-" + hash, {
            id: "mi-center-" + hash, kind: "service_center", name: item.name, series: city.label,
            source_url: officialPage, checked_on: checkedOn, region: "CN",
            details: { fields: { 省份: province.label, 城市: city.label, 公开地址: item.address,
              目录筛选: "手机（官网公开目录快照）" } },
          });
        }
        coverage.set(key, { province: province.label, city: city.label, found: result.length, missing, state: "browsed" });
      } catch (error) {
        coverage.set(key, { province: province.label, city: city.label, state: "unverified", error: error.message.split("\n")[0] });
      }
    }
  } catch (error) {
    coverage.set(cityKey(province.label), { province: province.label, state: "unverified", error: error.message.split("\n")[0] });
  }
  await fs.mkdir(new URL(".", output), { recursive: true });
  await replaceJson(output, [...rows.values()]);
  await replaceJson(diagnostics, [...coverage.values()]);
  console.log({ province: province.label, citiesChecked: coverage.size, centers: rows.size });
}
console.log({ taskSpaceId: task.spaceId, pages: (await task.pages()).length, rows: rows.size });
