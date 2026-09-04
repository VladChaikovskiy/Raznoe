#!/usr/bin/env python3
"""Check that every vm.* / jam.* member the UI touches actually exists.

A missing member is a compile error, and compiling the Android sources needs an
Android SDK. This is a cheap stand-in that catches the common slip — a UI
renamed one way and the model the other — in a second instead of a CI round.
Deliberately conservative: it only reports names it is sure about.
"""
import re, sys, pathlib

def members(path):
    src = pathlib.Path(path).read_text(encoding='utf-8')
    found = set(re.findall(r'\b(?:fun|val|var)\s+([a-zA-Z_]\w*)', src))
    # `by mutableStateOf` properties are declared the same way, so they are
    # already covered by the pattern above.
    return found

vm = members('app/src/main/java/com/raznoe/katana/KatanaViewModel.kt')
jam = members('app/src/main/java/com/raznoe/katana/audio/JamPlayer.kt')

missing = []
for path in pathlib.Path('app/src/main/java/com/raznoe/katana/ui').rglob('*.kt'):
    src = path.read_text(encoding='utf-8')
    for recv, known in (('vm', vm), ('jam', jam)):
        for name in re.findall(rf'\b{recv}\.([a-zA-Z_]\w*)', src):
            if name not in known:
                missing.append(f"{path}: {recv}.{name} not found")

for m in sorted(set(missing)):
    print("MISSING:", m)
print(f"checked, {len(set(missing))} missing member(s)")
sys.exit(1 if missing else 0)
