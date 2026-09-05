#!/usr/bin/env python3
"""Catch properties used before they are initialised.

Kotlin runs property initialisers and `init` blocks in DECLARATION order. A
direct reference to a property declared lower down is a compile error, but a
reference made through a member function is not — the compiler cannot see it,
so the field is simply still null when the constructor reaches it and the app
dies with a NullPointerException before it draws anything.

That is exactly what stopped "Katana by Vlad_i_c" from opening: `init` called
`refreshRawPatches()`, which touches `rawPatches`, declared 500 lines below.

The check: for every class with an `init` block, walk the member functions that
block calls (transitively) and make sure every property they touch is declared
above the init block.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main" / "java"

MEMBER = r"^    (?:(?:private|internal|public|protected|override|open|final|lateinit|@\w+)\s+)*"
PROP_RE = re.compile(MEMBER + r"(?:val|var)\s+(\w+)")
FUN_RE = re.compile(MEMBER + r"fun\s+(?:<[^>]+>\s+)?(\w+)\s*\(")
INIT_RE = re.compile(r"^    init\s*\{")
IDENT_RE = re.compile(r"\b([A-Za-z_]\w*)\b")
CALL_RE = re.compile(r"\b([a-z]\w*)\s*\(")


def block_end(lines: list[str], start: int) -> int:
    """Line index just past the block opening on `start` (brace counted)."""
    depth = 0
    for i in range(start, len(lines)):
        # Strings and comments are not worth parsing for brace counting here:
        # the sources use balanced braces inside both.
        depth += lines[i].count("{") - lines[i].count("}")
        if depth <= 0 and i > start:
            return i
        if depth == 0 and i == start and "{" in lines[i] and "}" in lines[i]:
            return i
    return len(lines) - 1


def has_backing_field(line: str) -> bool:
    """True when the declaration actually stores a value.

    `val x: T get() = ...` and an abstract/interface declaration have no field,
    so reading them early is harmless.
    """
    body = line.split("//")[0]
    if "get()" in body:
        return False
    return "=" in body or " by " in body


def check_file(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()

    props: dict[str, int] = {}
    funs: dict[str, tuple[int, int]] = {}
    inits: list[tuple[int, int]] = []

    for i, line in enumerate(lines):
        m = PROP_RE.match(line)
        if m and has_backing_field(line):
            props.setdefault(m.group(1), i)
            continue
        m = FUN_RE.match(line)
        if m:
            end = block_end(lines, i) if "{" in line else i
            funs.setdefault(m.group(1), (i, end))
            continue
        if INIT_RE.match(line):
            inits.append((i, block_end(lines, i)))

    problems = []
    for init_start, init_end in inits:
        seen: set[str] = set()
        # (function name, the call chain that reached it), breadth-first.
        queue = [(None, [])]
        while queue:
            fname, chain = queue.pop()
            if fname is None:
                body = lines[init_start : init_end + 1]
            else:
                if fname in seen or fname not in funs:
                    continue
                seen.add(fname)
                fs, fe = funs[fname]
                body = lines[fs : fe + 1]

            for offset, line in enumerate(body):
                code = line.split("//")[0]
                for ident in IDENT_RE.findall(code):
                    decl = props.get(ident)
                    if decl is None or decl <= init_end:
                        continue
                    where = " → ".join(chain) if chain else "init"
                    problems.append(
                        f"{path.relative_to(ROOT)}:{decl + 1}: '{ident}' is declared "
                        f"below the init block at line {init_start + 1}, but "
                        f"{where} reads it (see line "
                        f"{(init_start if fname is None else funs[fname][0]) + offset + 1})"
                        " — the field is still null there."
                    )
                for call in CALL_RE.findall(code):
                    if call in funs and call not in seen:
                        queue.append((call, chain + [call]))

    return problems


BAD_SAMPLE = '''\
class Sample {
    private val fine = ArrayDeque<String>()

    init {
        load()
    }

    fun load() {
        items.clear()
        fine.addLast("ok")
    }

    val items = mutableStateListOf<String>()
}
'''

GOOD_SAMPLE = '''\
class Sample {
    val items = mutableStateListOf<String>()
    val computed: Int get() = later

    init {
        load()
    }

    fun load() {
        items.clear()
    }

    private val later = 3
}
'''


def self_test() -> int:
    """Prove the checker still detects the bug it was written for."""
    import tempfile

    failures = []
    for name, source, want in (("bad", BAD_SAMPLE, True), ("good", GOOD_SAMPLE, False)):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "Sample.kt"
            path.write_text(source, encoding="utf-8")
            # check_file reports paths relative to ROOT, so keep it happy.
            global ROOT
            saved, ROOT = ROOT, Path(tmp)
            try:
                found = bool(check_file(path))
            finally:
                ROOT = saved
        if found != want:
            failures.append(
                f"self-test '{name}': expected {'a problem' if want else 'no problem'}, "
                f"got {'a problem' if found else 'no problem'}"
            )

    for f in failures:
        print(f)
    if failures:
        return 1
    print("check-init-order: self-test ok (detects the bug, ignores getters).")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    problems = []
    for path in sorted(SRC.rglob("*.kt")):
        problems.extend(check_file(path))

    # Duplicates are common: the same property read on several lines.
    unique = sorted(set(problems))
    if unique:
        print("Properties used before initialisation:\n")
        for p in unique:
            print("  " + p)
        print(f"\n{len(unique)} problem(s). Move the declaration above the init block.")
        return 1

    print("check-init-order: no properties are read before they are initialised.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
