"""Static check of the adapter mixins against one Minecraft version, without starting the game.

    python tools/mixin_check.py <adapter dir, e.g. v1_21_10 or legacy/v1_8_9> [<minecraft jar>]

Reads the adapter's preprocessed sources (build/generated/shared-src plus its own src/main/java; run
`gradlew :<adapter>:sharedSources` first) and checks every mixin in gg/aether/client/mixin against the Mojang-mapped
Minecraft jar Loom made for that version:
  - @Mixin targets exist
  - injector `method = ...` selectors match a method (name, and descriptor when given)
  - @At(value = "INVOKE" / "FIELD", target = ...) call sites occur inside the selected methods
  - @Inject handler parameters are exactly the target method's parameters (or none, just the CallbackInfo)
  - @Shadow fields / methods, @Accessor fields and @Invoker methods exist (superclasses included)
Prints one line per problem and exits 1 if there were any. The in-game `runAudit` task is the final check.
"""
import glob, os, re, shutil, subprocess, sys, tempfile
from functools import lru_cache
from pathlib import Path

RUNTIME = Path(__file__).resolve().parent.parent
JAVAP = os.environ.get("JAVAP") or next(
    (p for p in glob.glob("C:/Program Files/Java/jdk-*/bin/javap.exe")), "javap")
PRIM = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I", "long": "J", "float": "F", "double": "D"}


def find_jar(mc, yarn=False):
    base = Path.home() / ".gradle/caches/fabric-loom/minecraftMaven/net/minecraft"
    if yarn:  # Legacy Fabric: Legacy Yarn names. Its paths exceed Windows' MAX_PATH, so work on a short copy.
        cands = [p for p in base.glob(f"*/{mc}-net.legacyfabric.yarn*/*.jar") if not p.name.endswith("-sources.jar")]
        if not cands:
            return None
        src = max(cands, key=lambda p: os.stat(long_path(p)).st_mtime)
        dst = Path(tempfile.gettempdir()) / f"aether-mixin-check-{mc}.jar"
        if not dst.exists() or os.stat(long_path(src)).st_mtime > dst.stat().st_mtime:
            shutil.copyfile(long_path(src), dst)
        return str(dst)
    for kind in ("minecraft-merged", "minecraft-clientOnly", "minecraft-client"):
        cands = [p for p in base.glob(f"{kind}/{mc}-*/*.jar") if "yarn" not in str(p) and "intermediary" not in str(p)]
        if cands:
            return str(max(cands, key=lambda p: p.stat().st_mtime))
    p = Path.home() / f".gradle/caches/fabric-loom/{mc}/minecraft-client.jar"
    return str(p) if p.exists() else None


def long_path(p):
    p = str(p)
    prefix = "\\\\?\\"
    return prefix + p if os.name == "nt" and not p.startswith(prefix) else p


class ClassInfo:
    def __init__(self, name):
        self.name = name
        self.super = None
        self.interfaces = []
        self.fields = {}      # name -> descriptor
        self.methods = []     # (name, descriptor, [bytecode refs])


@lru_cache(maxsize=None)
def load(jar, cls):
    """Parse `javap -p -s -c` of cls ("a/b/C"); None if missing."""
    r = subprocess.run([JAVAP, "-p", "-s", "-c", "-cp", jar, cls.replace("/", ".")], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0 or "Compiled from" not in r.stdout and "class " not in r.stdout:
        return None
    info = ClassInfo(cls)
    lines = r.stdout.splitlines()
    simple = cls.split("/")[-1].split("$")[-1]
    cur = None
    for i, l in enumerate(lines):
        m = re.match(r"^(?:public |protected |private |abstract |final |static |sealed |non-sealed |strictfp )*(?:class|interface|enum|record) (\S+)(?: extends (\S+?))?(?: implements (.+?))? ?\{", l.strip())
        if m and not l.startswith(" "):
            if m.group(2):
                info.super = m.group(2).split("<")[0].replace(".", "/")
                if m.group(2) and "interface" in l.split(m.group(1))[0]:
                    info.interfaces += [x.strip().split("<")[0].replace(".", "/") for x in m.group(2).split(",")]
                    info.super = None
            if m.group(3):
                info.interfaces += [x.strip().split("<")[0].replace(".", "/") for x in re.split(r",(?![^<]*>)", m.group(3))]
            continue
        if l.startswith("  ") and not l.startswith("   ") and l.rstrip().endswith(";"):
            decl = l.strip()[:-1]
            desc = lines[i + 1].strip()[len("descriptor: "):] if i + 1 < len(lines) and lines[i + 1].strip().startswith("descriptor:") else ""
            if "(" in decl:
                before = decl.split("(")[0].split()
                name = before[-1]
                if name.replace("$", ".").split(".")[-1] == simple or name == cls.replace("/", "."):
                    name = "<init>"
                if name == "{}" or decl.startswith("static {}"):
                    name = "<clinit>"
                cur = [name, desc, []]
                info.methods.append(cur)
            else:
                name = decl.split()[-1]
                info.fields[name] = desc
                cur = None
            continue
        if cur is not None:
            m = re.search(r"// (Method|InterfaceMethod|Field) ([\w/$]+)\.([\w<>$\"]+):(\S+)", l)
            if m:
                cur[2].append((m.group(1), m.group(2), m.group(3).strip('"'), m.group(4)))
            elif re.search(r"//\s*(Method|InterfaceMethod|Field) ([\w<>$\"]+):(\S+)", l):
                # same-class reference: "// Method name:desc", "// Field name:desc"
                mm = re.search(r"//\s*(Method|InterfaceMethod|Field) ([\w<>$\"]+):(\S+)", l)
                cur[2].append((mm.group(1), cls, mm.group(2).strip('"'), mm.group(3)))
    return info


def hierarchy(jar, cls):
    seen, todo = [], [cls]
    while todo:
        c = todo.pop(0)
        if c in seen or c is None or c.startswith("java/"):
            continue
        seen.append(c)
        info = load(jar, c)
        if info:
            todo.append(info.super)
            todo += info.interfaces
    return [load(jar, c) for c in seen if load(jar, c)]


# ---- source parsing -----------------------------------------------------------------------------------------------

def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def balanced(src, start, open_c="(", close_c=")"):
    """Text between the bracket at src[start] and its match."""
    depth, i, in_str = 0, start, False
    while i < len(src):
        c = src[i]
        if c == '"' and src[i - 1] != "\\":
            in_str = not in_str
        elif not in_str:
            if c == open_c:
                depth += 1
            elif c == close_c:
                depth -= 1
                if depth == 0:
                    return src[start + 1:i], i
        i += 1
    return src[start + 1:], len(src)


def annotations(src):
    """[(name, args text, position of the end)] for every annotation."""
    out = []
    for m in re.finditer(r"@(\w+)\s*(\()?", src):
        if m.group(2):
            args, end = balanced(src, m.end() - 1)
            out.append((m.group(1), args, end + 1))
        else:
            out.append((m.group(1), "", m.end()))
    return out


def strings(text):
    consts = {}
    return re.findall(r'"((?:[^"\\]|\\.)*)"', text)


def resolve_type(t, imports, pkg_classes):
    t = re.sub(r"<.*>", "", t).replace("final ", "").strip()
    t = re.sub(r"@\w+(\([^)]*\))?\s*", "", t).strip()
    arr = ""
    while t.endswith("[]") or t.endswith("..."):
        arr += "["
        t = t[:-2] if t.endswith("[]") else t[:-3]
    t = t.strip()
    if t in PRIM:
        return arr + PRIM[t]
    first = t.split(".")[0]
    if first in imports:
        fq = imports[first] + t[len(first):].replace(".", "$")
    elif "." in t and t[0].islower():
        # fully qualified: the first capitalised part is the class, the rest are nested classes
        parts = t.split(".")
        k = next(i for i, p in enumerate(parts) if p[0].isupper())
        fq = ".".join(parts[:k + 1]) + "".join("$" + p for p in parts[k + 1:])
    elif first in ("String", "Object", "Integer", "Long", "Float", "Double", "Boolean", "Iterable", "Runnable", "Class"):
        fq = "java.lang." + t
    else:
        fq = imports.get("*", "") + t
    return arr + "L" + fq.replace(".", "/") + ";"


def param_descs(params, imports):
    out = []
    depth, cur = 0, ""
    for c in params:
        if c == "<":
            depth += 1
        elif c == ">":
            depth -= 1
        if c == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += c
    if cur.strip():
        out.append(cur)
    res = []
    for p in out:
        p = re.sub(r"@\w+(\([^)]*\))?\s*", "", p).strip()
        p = re.sub(r"\bfinal\s+", "", p)
        typ = p.rsplit(None, 1)[0] if " " in p else p
        res.append(resolve_type(typ, imports, None))
    return res


def split_desc(desc):
    params = desc[1:desc.index(")")]
    out, i = [], 0
    while i < len(params):
        j = i
        while params[j] == "[":
            j += 1
        if params[j] == "L":
            j = params.index(";", j)
        out.append(params[i:j + 1])
        i = j + 1
    return out


def check(adapter, jar=None):
    adir = RUNTIME / adapter
    gradle = (adir / "build.gradle.kts").read_text(encoding="utf-8")
    mc = re.search(r'extra\["mc"\] = "([^"]+)"', gradle).group(1)
    jar = jar or find_jar(mc, 'extra["mappings"] = "yarn"' in gradle)
    if not jar:
        sys.exit(f"no Minecraft jar for {mc}: build the adapter once so Loom makes it")
    files = {}
    for root in (adir / "build/generated/shared-src", adir / "src/main/java"):
        for f in (root / "gg/aether/client/mixin").glob("*.java"):
            files[f.name] = f  # own src wins
    problems = []
    for f in sorted(files.values()):
        raw = f.read_text(encoding="utf-8")
        if "@Mixin" not in raw:
            continue
        src = strip_comments(raw)
        imports = {m.group(2): m.group(1) + "." + m.group(2) for m in re.finditer(r"import\s+([\w.]+)\.(\w+)\s*;", src)}
        consts = dict(re.findall(r'static final String (\w+)\s*=\s*"([^"]*)"', src))
        anns = annotations(src)
        targets = []
        for name, args, _ in anns:
            if name == "Mixin":
                for m in re.finditer(r"([\w.]+)\.class", args):
                    targets.append(resolve_type(m.group(1), imports, None)[1:-1])
                for s in re.findall(r'targets\s*=\s*\{?\s*"([^"]+)"', args):
                    targets.append(s.replace(".", "/"))
                break
        where = f.stem
        infos = []
        for t in targets:
            i = load(jar, t)
            if not i:
                problems.append(f"{where}: target class {t} not found")
            else:
                infos.append(i)
        if not infos:
            continue
        hier = []
        for i in infos:
            hier += hierarchy(jar, i.name)

        def methods_named(sel):
            sel = consts.get(sel, sel)
            name, desc = (sel.split("(", 1)[0], "(" + sel.split("(", 1)[1]) if "(" in sel else (sel, None)
            return [m for i in infos for m in i.methods if m[0] == name and (desc is None or m[1] == desc)], name, desc

        for idx, (name, args, end) in enumerate(anns):
            if name in ("Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyConstant", "ModifyVariable", "ModifyExpressionValue",
                        "ModifyReturnValue", "WrapOperation", "WrapWithCondition"):
                mm = re.search(r"method\s*=\s*(\{[^}]*\}|\"[^\"]*\"|\w+)", args)
                if not mm:
                    problems.append(f"{where}: @{name} without method")
                    continue
                sels = re.findall(r'"([^"]*)"', mm.group(1)) or [mm.group(1)]
                selected = []
                for sel in sels:
                    found, mname, mdesc = methods_named(sel)
                    if not found:
                        problems.append(f"{where}: @{name} method {consts.get(sel, sel)} not in {'/'.join(i.name.split('/')[-1] for i in infos)}")
                    selected += found
                if not selected:
                    continue
                # INVOKE / FIELD target inside the selected methods
                for at in re.finditer(r"@At\s*\(", args):
                    at_args, _ = balanced(args, at.end() - 1)
                    kind = re.search(r'value\s*=\s*"(\w+)"', at_args) or re.match(r'\s*"(\w+)"', at_args)
                    kind = kind.group(1) if kind else ""
                    tm = re.search(r'target\s*=\s*"([^"]+)"', at_args)
                    if kind in ("INVOKE", "INVOKE_ASSIGN", "FIELD") and tm:
                        t = tm.group(1)
                        m2 = re.match(r"L([\w/$]+);([\w<>$]+)(?::)?(.*)", t)
                        if not m2:
                            continue
                        owner, member, desc = m2.groups()
                        ok = any(r[2] == member and (not desc or r[3] == desc) and (r[1] == owner or True)
                                 for s in selected for r in s[2])
                        if not ok:
                            problems.append(f"{where}: @{name} on {sels[0]}: no {kind} {owner.split('/')[-1]}.{member}{desc} inside")
                # handler parameters vs. target parameters (@Inject only)
                if name == "Inject":
                    hm = re.search(r"\)\s*(?:@\w+\s*(?:\([^)]*\))?\s*)*(?:(?:private|public|protected|static|final|synchronized|<[^>]+>)\s+)*[\w<>\[\], ?.$]+\s+([\w$]+)\s*\(", src[end - 1:])
                    if hm:
                        p0 = end - 1 + hm.end() - 1
                        params, _ = balanced(src, p0)
                        hp = param_descs(params, imports)
                        ci = [i for i, d in enumerate(hp) if d.endswith("CallbackInfo;") or d.endswith("CallbackInfoReturnable;")]
                        if ci:
                            given = hp[:ci[0]]
                            # Mixin wants all of the target's parameters or none of them.
                            # A method type variable (<E extends Entity> ... E entity) stands for any object type.
                            def same(want, have):
                                return len(want) == len(have) and all(w == h or (re.fullmatch(r"L[A-Z];", h) and w.startswith("L")) for w, h in zip(want, have))
                            if given and not any(same(split_desc(s[1]), given) for s in selected):
                                problems.append(f"{where}.{hm.group(1)}: parameters {given} don't match {sels[0]} {[s[1] for s in selected][:2]}")
            elif name == "Shadow":
                rest = src[end:end + 300]
                dm = re.match(r"\s*(?:@\w+(?:\([^)]*\))?\s*)*((?:(?:private|public|protected|static|final|abstract)\s+)*)([\w<>\[\], ?.]+?)\s+(\w+)\s*([;(=])", rest)
                if dm:
                    member, is_method = dm.group(3), dm.group(4) == "("
                    ok = any((member in [m[0] for m in i.methods]) if is_method else (member in i.fields) for i in hier)
                    if not ok:
                        problems.append(f"{where}: @Shadow {'method' if is_method else 'field'} {member} not found")
            elif name in ("Accessor", "Invoker"):
                v = re.search(r'"([^"]+)"', args)
                if not v:
                    continue
                member = v.group(1)
                if name == "Accessor":
                    ok = any(member in i.fields for i in hier)
                else:
                    ok = any(member in [m[0] for m in i.methods] for i in hier)
                if not ok:
                    problems.append(f"{where}: @{name}(\"{member}\") not found")
    return mc, problems


if __name__ == "__main__":
    mc, problems = check(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
    for p in problems:
        print(p)
    print(f"{sys.argv[1]} ({mc}): {len(problems)} problem(s)")
    sys.exit(1 if problems else 0)
