#!/usr/bin/env python3
"""Find Kotlin property/function JVM signature clashes.

A `var foo` generates setFoo()/getFoo() on the JVM, so a `fun setFoo(...)` or
`fun getFoo()` in the same class fails to compile with "Platform declaration
clash". The Android sources cannot be compiled in this environment, so this
catches the mistake before CI does — it has already cost two builds.
"""
import re, sys, pathlib

bad = []
for p in pathlib.Path('app/src/main/java').rglob('*.kt'):
    src = p.read_text(encoding='utf-8')
    # Only a `var` generates a setter; a `val` generates just the getter, so
    # `fun setModelId()` next to `val modelId` is fine.
    vars_ = set(re.findall(r'\bvar\s+([a-zA-Z_]\w*)\s*(?:by|:|=)', src))
    vals = set(re.findall(r'\bva[lr]\s+([a-zA-Z_]\w*)\s*(?:by|:|=)', src))
    for prefix, rest in set(re.findall(r'\bfun\s+(set|get)([A-Z]\w*)\s*\(', src)):
        name = rest[0].lower() + rest[1:]
        clashes = vars_ if prefix == 'set' else vals
        if name in clashes:
            bad.append(f"{p}: fun {prefix}{rest}() clashes with {'var' if prefix=='set' else 'property'} '{name}'")

for b in bad:
    print("CLASH:", b)
print(f"checked, {len(bad)} clash(es)")
sys.exit(1 if bad else 0)
