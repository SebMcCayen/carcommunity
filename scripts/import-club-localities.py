#!/usr/bin/env python3
"""Rebuild the offline club picker from two pinned, public SCB workbooks.

Standard library only. No credentials, cloud writes, or app build required.
The optional --source-dir reads already-downloaded, checksum-verified inputs.
"""

import argparse
from collections import defaultdict
import hashlib
import io
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


BASE = (
    "https://www.scb.se/hitta-statistik/statistik-efter-amne/"
    "boende-bebyggelse-och-mark/bebyggelseomraden/tatorter-och-smaorter/"
    "pong/tabell-och-diagram/"
)
SOURCES = {
    "tatorter-2023.xlsx": (
        BASE + "statistiska-tatorter-2023-befolkning-och-landareal-per-tatort-och-kommun/",
        "d743b0df767ee267e715545e4809c0ea51c5102d56af12088183a9610e6793e9",
    ),
    "changes-2023.xlsx": (
        BASE + "avregistrerade-och-nyregistrerade-statistiska-tatorter-och-smaorter-2020-till-2023-andring-av-koder-och-beteckningar/",
        "c94b88291221f92ee1e58412c9af983d1dbc44cc1c8905e91f01f6f3ffd9242d",
    ),
}
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "apps/android/app/src/main/assets/clubs/scb-localities-2023.tsv"
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
LOCALITY_CODE = re.compile(r"\d{4}T[BC]\d{3}")
HEADER = "# kcc-club-localities-v1; SCB 2023; see docs/features/club-location-catalog.md"


def read_source(name, source_dir):
    url, digest = SOURCES[name]
    if source_dir:
        data = (source_dir / name).read_bytes()
    else:
        with urllib.request.urlopen(url, timeout=45) as response:
            data = response.read(2_000_001)
    if len(data) > 2_000_000 or hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"SCB input changed or exceeded size bound: {name}; review before repinning")
    return data


def workbook_rows(data):
    """Read cell values from these single-sheet OOXML inputs without Excel deps."""
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if sum(info.file_size for info in archive.infolist()) > 12_000_000:
            raise ValueError("Workbook exceeds uncompressed size bound")
        shared = ET.fromstring(archive.read("xl/sharedStrings.xml"))
        strings = ["".join(node.itertext()) for node in shared.findall("s:si", NS)]
        sheet = ET.fromstring(archive.read("xl/worksheets/sheet1.xml"))
        for row in sheet.findall("s:sheetData/s:row", NS):
            values = [""] * 27
            for cell in row.findall("s:c", NS):
                column = 0
                for char in re.match(r"[A-Z]+", cell.attrib["r"])[0]:
                    column = column * 26 + ord(char) - ord("A") + 1
                if column > len(values):
                    raise ValueError("Unexpected SCB column")
                value = cell.find("s:v", NS)
                text = value.text if value is not None else ""
                if cell.attrib.get("t") == "s":
                    text = strings[int(text)]
                elif cell.attrib.get("t") == "inlineStr":
                    text = "".join(cell.find("s:is", NS).itertext())
                values[column - 1] = (text or "").strip()
            yield values


def make_catalog(locality_rows, change_rows):
    counties = {}
    for row in change_rows:
        if row[0].isdigit() and len(row[0]) <= 2 and row[1].endswith(" län"):
            code = row[0].zfill(2)
            if code in counties and counties[code] != row[1]:
                raise ValueError("Conflicting county names")
            counties[code] = row[1]
    if len(counties) != 21:
        raise ValueError("Expected all 21 counties")

    names = {}
    memberships = defaultdict(set)
    municipality_rows = 0
    for row in locality_rows:
        code = row[4]
        if not LOCALITY_CODE.fullmatch(code):
            continue  # Only header/documentation rows in the pinned workbook.
        county = row[0].zfill(2)
        if county not in counties or not re.fullmatch(r"\d{4}", row[2]) or row[2][:2] != county:
            raise ValueError("Invalid county/municipality membership")
        if not row[5] or not row[3] or (code in names and names[code] != row[5]):
            raise ValueError("Missing or conflicting locality name")
        names[code] = row[5]
        memberships[county, code].add(row[3])
        municipality_rows += 1
    if len(names) != 2017 or municipality_rows != 2144 or len(memberships) != 2030:
        raise ValueError("Incomplete 2023 locality coverage")

    aliases = {}
    for row in change_rows:
        if row[4] != "Tätort" or row[10] != "Avregistrerad" or not row[11].startswith("Av2_T."):
            continue
        # SCB's Kungsbacka row says "Tätort 2013" (source typo). The pinned
        # sheet nevertheless identifies 1480TC108; validate it against 2023.
        successor = re.match(r"Tätort \d{4}: (\d{4}T[BC]\d{3})(?:\.|$)", row[12])
        county, code = row[0].zfill(2), row[5]
        if not successor or not LOCALITY_CODE.fullmatch(code) or code in names or not row[6]:
            raise ValueError("Unresolved merged locality")
        target = successor[1]
        if (county, target) not in memberships or row[2][:2] != county:
            raise ValueError("Merged locality has no successor in its county")
        key = county, code
        if key in aliases:
            raise ValueError("Duplicate merged locality")
        aliases[key] = (row[6], row[3], target)
    if len(aliases) != 32:
        raise ValueError("Incomplete merged-locality coverage")

    rows = [["C", code, name] for code, name in sorted(counties.items())]
    rows += [["L", county, code, names[code], ", ".join(sorted(municipalities))]
             for (county, code), municipalities in sorted(memberships.items())]
    rows += [["A", county, code, name, municipality, target]
             for (county, code), (name, municipality, target) in sorted(aliases.items())]
    for row in rows:
        if any(not value or any(char in value for char in "\t\r\n") for value in row):
            raise ValueError("Invalid TSV field")
        if row[0] != "C" and not 2 <= len(f"{row[3]}, {counties[row[1]]}") <= 80:
            raise ValueError("Location would exceed callable string contract")
    return HEADER + "\n" + "\n".join("\t".join(row) for row in rows) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path)
    parser.add_argument("--check", action="store_true", help="Verify asset without writing it")
    args = parser.parse_args()
    localities = list(workbook_rows(read_source("tatorter-2023.xlsx", args.source_dir)))
    changes = list(workbook_rows(read_source("changes-2023.xlsx", args.source_dir)))
    catalog = make_catalog(localities, changes).encode("utf-8")
    if args.check:
        if OUTPUT.read_bytes() != catalog:
            raise SystemExit("Bundled club geography differs from the pinned SCB inputs")
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_bytes(catalog)
    print(f"SCB catalog: 21 counties, 2017 localities, 2030 county memberships, 32 merged names; {len(catalog)} bytes")


if __name__ == "__main__":
    main()
