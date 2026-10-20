#!/usr/bin/env python3
"""按 tmdb_id 从 TMDB 拉详情，生成只填空值字段的 UPDATE SQL（含缺演职员影片的 crew 补插）。

用法: python tools/tmdb_backfill.py > tools/_tmdb_backfill.sql
凭据读 config/tmdb-local.yml（read-access-token / proxy-url），与后端同一套。
字段映射与 TmdbMovieService 一致：area=production_countries 中文名、cat=genres、
summary=overview、runtime_min=runtime、dir=Director、language=original_language 中文名。
只生成 SET 中目标列为 NULL/'' 的条件更新（WHERE 里再兜一层），不覆盖已有数据。
"""
import json, re, sys, time, urllib.request, urllib.error

CFG = open("config/tmdb-local.yml", encoding="utf-8").read()
TOKEN = re.search(r'read-access-token:\s*"([^"]+)"', CFG).group(1)
m = re.search(r"proxy-url:\s*(\S+)", CFG)
PROXY = m.group(1) if m else None

IDS = [
    (903, 1726805), (920, 1778650), (921, 1529692), (924, 1796001), (927, 1796449),
    (928, 1751502), (930, 1789520), (932, 1785018), (933, 1783608), (934, 1774598),
    (936, 1779769), (937, 1765410), (939, 1764816), (940, 1761059), (941, 1761208),
    (943, 736212), (945, 1793081), (947, 1760508), (948, 1306055), (949, 1213243),
    (952, 1745822), (953, 1751293), (954, 1745988), (956, 1741921), (957, 1789580),
    (959, 1735402), (960, 1708565),
]
NO_CREW = {932, 947, 960}  # 还需要补 movie_crew 的 movie_id

# 与后端 java.util.Locale(..., SIMPLIFIED_CHINESE) 对齐的中文名映射（本批影片涉及的国家/语言）
COUNTRY = {"CN": "中国", "HK": "中国香港", "TW": "中国台湾", "US": "美国", "KR": "韩国",
           "JP": "日本", "GB": "英国", "FR": "法国", "DE": "德国", "TH": "泰国", "AU": "澳大利亚"}
LANG = {"zh": "汉语普通话", "cn": "汉语普通话", "ko": "韩语", "en": "英语", "ja": "日语",
        "fr": "法语", "de": "德语", "th": "泰语", "yue": "粤语"}

opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}) if PROXY else urllib.request.ProxyHandler())

def fetch(tmdb_id):
    url = (f"https://api.themoviedb.org/3/movie/{tmdb_id}"
           "?language=zh-CN&append_to_response=credits,images,release_dates")
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {TOKEN}",
                                               "accept": "application/json"})
    with opener.open(req, timeout=20) as r:
        return json.load(r)

def q(s):  # SQL 字符串转义
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

out = []
for mid, tid in IDS:
    try:
        d = fetch(tid)
    except Exception as e:
        print(f"-- !! movie {mid} tmdb {tid} 拉取失败: {e}", file=sys.stderr)
        continue
    sets, wheres = [], []
    def fill(col, val):
        if val is None or val == "":
            return
        sets.append(f"{col}={q(str(val))}")
        wheres.append(f"({col} IS NULL OR {col}='')")
    countries = [COUNTRY.get(c.get("iso_3166_1"), c.get("name")) for c in d.get("production_countries", [])]
    countries = [c for c in countries if c]
    fill("area", ",".join(countries))
    if d.get("runtime"):
        fill("runtime_min", d["runtime"])
        fill("time", d["runtime"])
    genres = [g["name"] for g in d.get("genres", []) if g.get("name")]
    fill("cat", ",".join(genres))
    if d.get("overview"):
        fill("summary", d["overview"].strip())
    ol = d.get("original_language")
    if ol:
        fill("language", LANG.get(ol, ol))
    directors = [p["name"] for p in d.get("credits", {}).get("crew", []) if p.get("job") == "Director"]
    fill("dir", ",".join(dict.fromkeys(directors)))
    if not sets:
        print(f"-- movie {mid} tmdb {tid}: 无可补字段", file=sys.stderr)
        continue
    where = " OR ".join(wheres) if wheres else "1=0"
    out.append(f"UPDATE movie SET {', '.join(sets)} WHERE id={mid} AND ({where});")
    if mid in NO_CREW:
        out.append(f"DELETE FROM movie_crew WHERE movie_id={mid};")
        persons = []  # (tmdb_person_id, name, role, character)
        for p in d.get("credits", {}).get("crew", []):
            job = p.get("job")
            role = 2 if job == "Director" else 3 if job in ("Writer", "Screenplay", "Story") else 0
            if role:
                persons.append((p["id"], p["name"], role, None))
        for p in d.get("credits", {}).get("cast", [])[:12]:
            persons.append((p["id"], p["name"], 1, p.get("character")))
        for n, (pid, name, role, ch) in enumerate(persons):
            # person 按 tmdb_id 去重：不存在才插入，再按 tmdb_id 取回自增 id
            out.append(f"INSERT INTO person(tmdb_id,name) SELECT {pid},{q(name)} FROM DUAL "
                       f"WHERE NOT EXISTS(SELECT 1 FROM person WHERE tmdb_id={pid});")
            out.append(f"INSERT INTO movie_crew(movie_id,person_id,role_type,character_name,sort_order) "
                       f"SELECT {mid},id,{role},{q(ch) if ch else 'NULL'},{n} FROM person WHERE tmdb_id={pid} LIMIT 1;")
    time.sleep(0.3)

sys.stdout.reconfigure(encoding="utf-8")
print("\n".join(out))
