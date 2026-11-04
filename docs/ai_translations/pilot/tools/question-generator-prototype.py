#!/usr/bin/env python3
"""Question generator: a throwaway PROTOTYPE, not a product.

Reads a parsed-records file (Stroom `records:2`) and the event-logging XSD, and prints the questions a system could
ask **without any model**, and the decisions it can settle from the schema or the data. It exists to test two ideas
from the design note (section 2.4 and 6.3): that the ambiguity rule and the required-fields rule can be found
mechanically, and that the result matches the decision points written by hand for the pilot cases.

It is deliberately small and crude (a few patterns, a tiny synonym table, one target event shape: Network). It does
not write a translation. It uses only the Python standard library.

Usage:
    python3 question-generator-prototype.py --schema event_logging_v4_1_0....xsd --input parsed-records.xml
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET

XS = "http://www.w3.org/2001/XMLSchema"
REC = "{records:2}"

# A tiny, explicit synonym table. Who owns it is an open question (design note, question 10).
ACTION_SYNONYMS = {
    "BLOCK": "Deny", "DROP": "Deny", "DENY": "Deny", "REJECT": "Deny",
    "ALLOW": "Permit", "ACCEPT": "Permit", "PERMIT": "Permit", "PASS": "Permit",
}

IPV4 = re.compile(r"^((25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])$")
IPV4_LIKE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")
IPV6_LIKE = re.compile(r"^[0-9a-fA-F:]*:[0-9a-fA-F:.]*$")
ISO_Z = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(Z|[+-]\d\d:\d\d)$")
ISO_NOZONE = re.compile(r"^\d{4}-\d\d-\d\d[T ]\d\d:\d\d:\d\d(\.\d+)?$")
DMY = re.compile(r"^(\d\d)/(\d\d)/(\d{4}) \d\d:\d\d:\d\d$")


# ---------------------------------------------------------------- schema

class Schema:
    def __init__(self, path):
        root = ET.parse(path).getroot()
        self.types = {c.get("name"): c for c in root.findall("{%s}complexType" % XS)}
        self.simple = {c.get("name"): c for c in root.findall("{%s}simpleType" % XS)}

    @staticmethod
    def local(name):
        return name.split(":")[-1] if name else name

    def children(self, type_name, seen=None):
        """The child elements of a complex type, with min and max occurs, base types included."""
        seen = seen or set()
        ct = self.types.get(self.local(type_name))
        if ct is None or type_name in seen:
            return []
        seen.add(type_name)
        out = []

        def walk(node):
            # Descend through sequences, choices and extensions, but NOT into an element's own inline type:
            # its children belong to that element, not to this type.
            for child in node:
                tag = child.tag.split("}")[1]
                if tag == "element":
                    if child.get("name"):
                        out.append((child.get("name"), child.get("type"),
                                    int(child.get("minOccurs", "1")), child.get("maxOccurs", "1")))
                elif tag == "extension":
                    out.extend(self.children(child.get("base"), seen))
                    walk(child)
                elif tag in ("sequence", "choice", "all", "complexContent"):
                    walk(child)

        walk(ct)
        return out

    def enum_values(self, simple_name):
        st = self.simple.get(self.local(simple_name))
        if st is None:
            return None
        values = [e.get("value") for e in st.iter("{%s}enumeration" % XS)]
        return values or None

    def action_choices(self):
        return [name for (name, _, _, _) in self.children("NetworkEventActionComplexType")]

    def required_leaves(self):
        """Required elements of the Network event shape that no input column can fill by type alone."""
        need = []
        for name, typ, mn, _ in self.children("EventTimeComplexType"):
            if mn >= 1:
                need.append(("Event/EventTime/" + name, "time"))
        for name, typ, mn, _ in self.children("EventSourceComplexType"):
            if mn < 1:
                continue
            if name == "System":
                for n2, t2, m2, _ in self.children("SystemComplexType"):
                    if m2 >= 1:
                        need.append(("Event/EventSource/System/" + n2, "constant"))
            elif name == "Device":
                need.append(("Event/EventSource/Device", "device"))
            else:
                need.append(("Event/EventSource/" + name, "constant"))
        for name, typ, mn, _ in self.children("EventDetailComplexType"):
            if name == "TypeId" and mn >= 1:
                need.append(("Event/EventDetail/TypeId", "type-id"))
        return need


# ---------------------------------------------------------------- input

def read_records(path):
    root = ET.parse(path).getroot()
    rows, names = [], []
    for rec in root.findall(REC + "record"):
        datas = rec.findall(REC + "data")
        rows.append([d.get("value", "") for d in datas])
        names.append([d.get("name") for d in datas])
    return rows, names


def classify(values):
    """A crude type for one column, from the values that are present."""
    present = [v for v in values if v not in ("", "-")]
    if not present:
        return "empty", {}
    n = len(present)
    info = {}
    if sum(bool(IPV4_LIKE.match(v)) or bool(IPV6_LIKE.match(v)) for v in present) >= max(2, n * 0.6):
        info["invalid"] = [v for v in present if IPV4_LIKE.match(v) and not IPV4.match(v)]
        return "address", info
    if sum(bool(ISO_Z.match(v)) for v in present) >= n * 0.6:
        info["zone"] = "explicit"
        return "time-iso", info
    if sum(bool(DMY.match(v)) for v in present) >= n * 0.6:
        firsts = [int(DMY.match(v).group(1)) for v in present if DMY.match(v)]
        seconds = [int(DMY.match(v).group(2)) for v in present if DMY.match(v)]
        info["day_first_proved"] = any(f > 12 for f in firsts)
        info["month_first_proved"] = any(s > 12 for s in seconds)
        return "time-dmy", info
    if all(v.isdigit() and 0 <= int(v) <= 65535 for v in present) and n >= 2:
        info["wellknown"] = [int(v) < 1024 for v in present]
        return "port", info
    if len(set(present)) <= 6 and all(v.isalpha() for v in present):
        return "enum", {"values": sorted(set(v.upper() for v in present))}
    if len(set(present)) == 1:
        return "constant", {"value": present[0]}
    return "other", {}


# ---------------------------------------------------------------- questions

def generate(schema, rows, names):
    asked, settled = [], []
    width = max(len(r) for r in rows)
    common = max(set(len(r) for r in rows), key=[len(r) for r in rows].count)
    short = [i + 1 for i, r in enumerate(rows) if len(r) != common]
    full = [r for r in rows if len(r) == common]
    cols = [[r[i] for r in full] for i in range(common)]
    types = [classify(c) for c in cols]
    labelled = [next((nm[i] for nm in names if len(nm) > i and nm[i]), None) for i in range(common)]

    # ---- required schema fields with no source in the input
    have_time = any(t[0].startswith("time") for t in types)
    have_host = any(t[0] == "constant" for t in types)
    action_col = next((i for i, t in enumerate(types) if t[0] == "enum"), None)
    missing = []
    for path, kind in schema.required_leaves():
        if kind == "time" and have_time:
            settled.append(("%s" % path, "filled from the timestamp column"))
        elif kind == "device" and have_host:
            settled.append((path, "filled from the column holding one repeated value (the logging host)"))
        elif kind == "type-id" and action_col is not None:
            missing.append("%s (or derive it from the action column, col %d)" % (path, action_col + 1))
        else:
            missing.append(path)
    if missing:
        asked.append(("required", "values the schema requires and no column supplies: " + "; ".join(missing),
                      "asked once per feed, and stored as feed configuration",
                      ["a constant you give", "a feed property", "the value other feeds with this schema use"]))

    # ---- which address is the source
    addr_cols = [i for i, t in enumerate(types) if t[0] == "address"]
    named_dir = [i for i in addr_cols if labelled[i] and labelled[i].lower() in ("src", "source", "dst", "dest", "destination")]
    if len(addr_cols) >= 2:
        if len(named_dir) >= 2:
            settled.append(("direction", "the columns are named %s: name evidence settles which is the source" %
                            ", ".join(labelled[i] for i in named_dir)))
        else:
            a, b = addr_cols[0], addr_cols[1]
            ports = [i for i, t in enumerate(types) if t[0] == "port"]
            idx = 0
            note = "no hint in the data"
            if len(ports) >= 2:
                for r_i, r in enumerate(full):
                    try:
                        p1, p2 = int(r[ports[0]]), int(r[ports[1]])
                    except ValueError:
                        continue
                    if (p1 < 1024) != (p2 < 1024):
                        idx = r_i
                        note = ("the ports on row %d point %s the column order, but port numbers are weak evidence"
                                % (r_i + 1, "against" if p1 < 1024 else "with"))
                        break
            r = full[idx]
            opts = []
            for first_is_source in (True, False):
                s, d = (a, b) if first_is_source else (b, a)
                sp = ports[0] if first_is_source else ports[1] if len(ports) > 1 else None
                dp = ports[1] if first_is_source else ports[0] if len(ports) > 1 else None
                ev = "Source %s%s, Destination %s%s" % (
                    r[s], (":" + r[sp]) if sp is not None and r[sp] else "",
                    r[d], (":" + r[dp]) if dp is not None and r[dp] else "")
                opts.append(("column %d is the source" % ((a if first_is_source else b) + 1), ev))
            asked.append(("direction", "which address is the source (columns %d and %d)" % (a + 1, b + 1),
                          note, opts))

    # ---- timestamps
    for i, (t, info) in enumerate(types):
        if t == "time-iso":
            settled.append(("time zone, col %d" % (i + 1), "every value carries Z or an offset: settled"))
        if t == "time-dmy":
            asked.append(("time zone", "col %d has no time zone" % (i + 1), "the schema needs UTC (Z)",
                          ["the times are UTC", "a named zone", "the feed's configured zone"]))
            if info["day_first_proved"] and not info["month_first_proved"]:
                settled.append(("date order, col %d" % (i + 1), "a value with day > 12 proves day-first"))
            elif info["month_first_proved"] and not info["day_first_proved"]:
                settled.append(("date order, col %d" % (i + 1), "a value with month-slot > 12 proves month-first"))
            else:
                asked.append(("date order", "col %d: day/month or month/day" % (i + 1),
                              "no value is above 12 in either slot", ["day first", "month first"]))

    # ---- enumerated action column
    schema_actions = schema.action_choices()
    if action_col is not None:
        for v in types[action_col][1]["values"]:
            exact = next((s for s in schema_actions if s.upper() == v), None)
            syn = ACTION_SYNONYMS.get(v)
            if exact:
                settled.append(("action %s" % v, "exact match to the schema's %s" % exact))
            elif syn:
                settled.append(("action %s" % v, "synonym table gives %s (a confirmation, not a question)" % syn))
            else:
                asked.append(("action", "%s has no match in the schema's network actions" % v,
                              "schema choices: %s" % ", ".join(schema_actions),
                              ["map to one of the schema actions", "drop the record"]))

    # ---- protocol outside the schema's list
    proto_enum = schema.enum_values("NetworkProtocolSimpleType") or []
    for i, c in enumerate(cols):
        present = {v.upper() for v in c if v}
        if proto_enum and present and present <= {p.upper() for p in proto_enum} | {"GRE", "SCTP", "ESP", "AH"} \
                and present & {p.upper() for p in proto_enum}:
            odd = sorted(v for v in present if v not in {p.upper() for p in proto_enum})
            if odd:
                asked.append(("protocol", "col %d has %s, outside the schema's list (%s)" %
                              (i + 1, ", ".join(odd), ", ".join(proto_enum)), "",
                              ["Other", "drop the record"]))
            else:
                settled.append(("protocol, col %d" % (i + 1), "every value is in the schema's list"))

    # ---- invalid values, placeholder tokens, short rows
    for i, (t, info) in enumerate(types):
        if t == "address" and info.get("invalid"):
            asked.append(("invalid value", "col %d has %s, which looks like an address and is not valid" %
                          (i + 1, ", ".join(info["invalid"])), "",
                          ["drop the record and report it", "keep the record without it", "keep it with the raw value"]))
    marker_cols = [i + 1 for i, c in enumerate(cols) if "-" in c and types[i][0] in ("address", "port", "other")]
    if marker_cols:
        asked.append(("missing-value marker", "col(s) %s use '-' for no value" % ", ".join(map(str, marker_cols)), "",
                      ["omit the element", "drop the record"]))
    for i, c in enumerate(cols):
        if "" in c:
            settled.append(("empty values, col %d" % (i + 1), "empty means absent, and the element is optional in the schema"))
    if short:
        asked.append(("wrong shape", "row(s) %s do not have %d fields" % (", ".join(map(str, short)), common), "",
                      ["drop the record and report it", "emit what there is"]))
    return asked, settled


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--schema", required=True)
    ap.add_argument("--input", required=True)
    args = ap.parse_args()
    schema = Schema(args.schema)
    rows, names = read_records(args.input)
    asked, settled = generate(schema, rows, names)
    print("QUESTIONS THE SYSTEM WOULD ASK (%d)" % len(asked))
    for n, (kind, what, why, opts) in enumerate(asked, 1):
        print("  %d. [%s] %s" % (n, kind, what))
        if why:
            print("       why: %s" % why)
        for o in opts:
            print("       - %s" % (o if isinstance(o, str) else "%s  ->  %s" % o))
    print("\nSETTLED WITHOUT ASKING (%d)" % len(settled))
    for kind, why in settled:
        print("  - %s: %s" % (kind, why))


if __name__ == "__main__":
    sys.exit(main())
