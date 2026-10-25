#!/usr/bin/env python3
"""Run the opt-in browser resource audit without adding diagnostics to normal tests."""
import csv
import json
import hashlib
from pathlib import Path
import subprocess
import re
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "tmp/asset-audit"
TARGET = ROOT / "web/src/jsTest/kotlin/world/phantasmal/web/questEditor/loading/AssetCompletenessAuditTests.kt"


def run(args, log):
    with log.open("w") as stream:
        subprocess.run(["rtk", "proxy", "./gradlew", *args], cwd=ROOT,
                       stdout=stream, stderr=subprocess.STDOUT, check=True)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with (OUTPUT / "asset-manifest.tsv").open("w") as stream:
        stream.write("path\tbytes\tsha256\n")
        for path in sorted((ROOT / "web/src/jsMain/resources/assets").rglob("*")):
            if path.is_file():
                data = path.read_bytes()
                stream.write(f"{path.relative_to(ROOT)}\t{len(data)}\t{hashlib.sha256(data).hexdigest()}\n")
    run([":web:assets-generation:auditQuestObjectVariants"], OUTPUT / "corpus.log")
    with (OUTPUT / "object-occurrences.tsv").open() as stream:
        variants = sorted({(r["type"], r["model"]) for r in csv.DictReader(stream, delimiter="\t")})
    cases = ",\n".join(f"ObjectType.{name} to {'null' if model == '-' else model}" for name, model in variants)
    template = Path(__file__).with_name("AssetCompletenessAuditTests.kt").read_text()
    area_tests = "\n".join(
        f"@Test fun area_{ep}_{i}() = testAsync {{ areas(Episode.{ep}, {i}) }}"
        for ep, count in [("I", 18), ("II", 18), ("IV", 11)] for i in range(count)
    )
    assert not TARGET.exists(), f"Refusing to overwrite {TARGET}"
    pair_file = OUTPUT / "source/item-pairs.tsv"
    with pair_file.open() as stream:
        pairs = sorted({(int(r["model"]), int(r["source_texture"])) for r in csv.DictReader(stream, delimiter="\t")})
    xvm_paths = sorted((ROOT / "web/src/jsMain/resources/assets").rglob("*.xvm"))
    generated = template.replace("/* CORPUS_VARIANTS */", cases).replace("/* AREA_TESTS */", area_tests)
    generated = generated.replace("/* SOURCE_ITEM_PAIRS */", ",\n".join(f"{m} to {t}" for m, t in pairs))
    generated = generated.replace("/* XVM_PATHS */", ",\n".join(json.dumps("/" + str(p.relative_to(ROOT / "web/src/jsMain/resources/assets"))) for p in xvm_paths))
    TARGET.write_text(generated)
    import xml.etree.ElementTree as ET
    rows = []
    try:
        # Keep each Gradle console capture bounded; a single expanded suite exceeds
        # the TeamCity stdout parser's line buffer on this toolchain.
        names = re.findall(r"@Test fun (\w+)\(", generated)
        groups = [["characters"], [n for n in names if n.startswith("area_")],
                  [n for n in names if n != "characters" and not n.startswith("area_")]]
        for i, group in enumerate(groups):
            args = [":web:jsBrowserTest"]
            for name in group:
                args.extend(["--tests", f"*AssetCompletenessAuditTests.{name}"])
            run(args, OUTPUT / f"browser-{i}.log")
            batch = []
            for report in (ROOT / "web/build/test-results/jsBrowserTest").glob("*.xml"):
                for element in ET.parse(report).iter("system-out"):
                    for payload in (element.text or "").split("ASSET_AUDIT ")[1:]:
                        batch.append(json.JSONDecoder().raw_decode(payload.strip())[0])
            if not batch:
                raise RuntimeError(f"No audit rows in group {i}; inspect browser-{i}.log")
            rows.extend(batch)
    finally:
        TARGET.unlink()
    if not rows:
        raise RuntimeError("No audit rows found; inspect browser.log and test XML")
    ids = [r["id"] for r in rows]
    if len(ids) != len(set(ids)):
        raise RuntimeError("Duplicate diagnostic case IDs")
    for prefix, expected in [("player-animation/", 572), ("source-item/", len(pairs)),
                             ("standalone-texture/", len(xvm_paths)), ("area/", 230),
                             ("character-skin-face/", 660), ("particle/", 47)]:
        actual = sum(i.startswith(prefix) for i in ids)
        if actual != expected:
            raise RuntimeError(f"Incomplete {prefix}: {actual}, expected {expected}")
    for row in rows:
        for request in row["requests"]:
            request["path"] = urlsplit(request["path"]).path
    (OUTPUT / "browser.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n")
    if any("asDynamic is not a function" in r["detail"] or "reading 'call'" in r["detail"] for r in rows):
        raise RuntimeError("Audit instrumentation failed; results cannot establish resource coverage")
    with (OUTPUT / "findings.tsv").open("w") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n", quoting=csv.QUOTE_ALL)
        writer.writerow(["case", "status", "detail", "failed_requests"])
        for row in rows:
            failed = [q["path"] for q in row["requests"] if q["status"] != 200]
            slots = re.search(r"missing_texture_slots=[0-9]", row["detail"])
            if row["status"] != "OK" or failed or slots:
                writer.writerow([row["id"], row["status"] if row["status"] != "OK" else "TEXTURE_REFERENCE",
                                 row["detail"], ",".join(failed)])
    print(f"Recorded {len(rows)} cases in {OUTPUT}")


if __name__ == "__main__":
    main()
