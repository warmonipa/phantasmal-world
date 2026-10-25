#!/usr/bin/env python3
"""Read-only source comparison. Writes evidence only; never installs or repairs assets.

Uses the existing independent psoarc readers from bb-psov4/tools.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import struct
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--psoarc-tools", type=Path, required=True)
    parser.add_argument("--client-data", type=Path, required=True)
    parser.add_argument("--item-map", type=Path, required=True)
    args = parser.parse_args()
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(args.psoarc_tools))
    from psoarc.afs import unpack as afs_unpack
    from psoarc.gsl import unpack as gsl_unpack
    from psoarc.prs import decompress
    from psoarc.bml import njtl_names

    root = Path(__file__).resolve().parents[2]
    assets = root / "web/src/jsMain/resources/assets"
    out = root / "tmp/asset-audit/source"
    out.mkdir(parents=True, exist_ok=True)
    sha = lambda data: hashlib.sha256(data).hexdigest()
    provenance = {"inputs": {}}
    def read(path):
        data = path.read_bytes()
        provenance["inputs"][str(path)] = {"bytes": len(data), "sha256": sha(data)}
        return data
    models = afs_unpack(read(assets / "items/ItemModelEp4.afs"))
    textures = afs_unpack(read(assets / "items/ItemTextureEp4.afs"))
    read(args.item_map)
    item_rows = list(csv.DictReader(args.item_map.open(), delimiter="\t"))
    pairs = {}
    for r in item_rows:
        m, t = int(r["model"]), int(r["texture"])
        if m >= 271 and 0 <= m < len(models) and 0 <= t < len(textures):
            pairs.setdefault((m, t), []).append(r)
    with (out / "item-pairs.tsv").open("w") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(["model", "current_texture", "source_texture", "category", "examples", "model_texture_names", "current_texture_count", "source_texture_count"])
        for (m, t), rows in sorted(pairs.items()):
            model = decompress(models[m])
            current = decompress(textures[m])
            correct = decompress(textures[t])
            names = njtl_names(model)
            writer.writerow([m, m, t, rows[0]["category"], " | ".join(r["item_name"] for r in rows[:3]), ",".join(names), struct.unpack_from("<I", current, 8)[0], struct.unpack_from("<I", correct, 8)[0]])

    gsl = dict(gsl_unpack(read(args.client_data / "data.gsl")))
    # Include loose files as the client does; record identical names' winning source.
    raw_sets = {name: (data, "data.gsl/" + name) for name, data in gsl.items() if name.startswith("map_") and name.endswith("o.dat")}
    for path in sorted(args.client_data.glob("map_*o.dat")):
        raw_sets[path.name] = (read(path), str(path))
    with (out / "free-object-occurrences.tsv").open("w") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(["source", "index", "type_id", "section", "param1", "param2", "param3", "param4", "param5", "param6"])
        for name, (data, source) in sorted(raw_sets.items()):
            if len(data) % 68:
                raise ValueError(f"Invalid raw object-set record size: {source}")
            for index in range(len(data) // 68):
                offset = index * 68
                writer.writerow([source, index, struct.unpack_from("<H", data, offset)[0], struct.unpack_from("<H", data, offset + 12)[0], *struct.unpack_from("<fffiii", data, offset + 40)])

    # Exact legacy-parser slice matches identify assets damaged by that extraction mechanism.
    bundled = {}
    for directory in ("npcs", "objects"):
        for path in sorted((assets / directory).iterdir()):
            if path.suffix in (".nj", ".xj", ".xvm", ".njm"):
                bundled.setdefault(sha(path.read_bytes()), []).append(str(path.relative_to(assets)))
    bml_rows = []
    for name, data in sorted(gsl.items()):
        if not name.endswith(".bml"):
            continue
        count = struct.unpack_from("<I", data, 4)[0]
        table_end = 64 + count * 64
        correct_start = (table_end + 2047) // 2048 * 2048
        old_offset = max(2048, table_end)
        if old_offset == correct_start:
            continue
        row = {"archive": name, "sha256": sha(data), "count": count, "old_start": old_offset, "aligned_start": correct_start, "raw_matches": []}
        for index in range(count):
            header = 64 + index * 64
            entry = data[header:header + 32].split(b"\0")[0].decode("ascii")
            model_size, _, _, texture_size = struct.unpack_from("<4I", data, header + 32)
            for kind, size in (("model", model_size), ("texture", texture_size)):
                matches = bundled.get(sha(data[old_offset:old_offset + size]), []) if size else []
                if matches:
                    row["raw_matches"].append({"entry": entry, "kind": kind, "offset": old_offset, "bytes": size, "assets": matches})
                old_offset += (size + 31) // 32 * 32
        bml_rows.append(row)
    (out / "bml-layout-scope.json").write_text(json.dumps(bml_rows, indent=2) + "\n")

    # Original BB SetData tables; bounded direct pointer walk, same format as psolib/newserv.
    tables = {n: b for n, b in gsl.items() if n.lower().startswith("setdatatable") and n.endswith(".rel")}
    tables.update({p.name: read(p) for p in args.client_data.glob("SetDataTable*.rel")})
    map_rows = []
    for name, data in sorted(tables.items()):
        u32 = lambda p: struct.unpack_from("<I", data, p)[0]
        ptr = u32(len(data) - 16)
        start = u32(ptr)
        if not (0 <= start < ptr < len(data)) or (ptr - start) % 8:
            raise ValueError(f"Invalid SetData root in {name}")
        for area in range((ptr - start) // 8):
            layout_table, layouts = struct.unpack_from("<II", data, start + area * 8)
            for layout in range(layouts):
                entity_table, entities = struct.unpack_from("<II", data, layout_table + layout * 8)
                for entity in range(entities):
                    strings = []
                    for p in struct.unpack_from("<III", data, entity_table + entity * 12):
                        strings.append(data[p:data.index(b"\0", p)].decode("ascii"))
                    map_rows.append([name, area, layout, entity, *strings])
    with (out / "setdata.tsv").open("w") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(["table", "map_id", "layout", "entities", "object", "enemy", "geometry"])
        writer.writerows(map_rows)
    provenance.update(raw_object_sets=len(raw_sets), setdata_tables=len(tables), mapped_item_pairs=len(pairs), oversized_bmls=len(bml_rows))
    (out / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(json.dumps({k: v for k, v in provenance.items() if k != "inputs"}))


if __name__ == "__main__":
    main()
