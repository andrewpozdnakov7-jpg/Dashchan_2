"""Slooop design inventory and new-dialog guard. Python 3.9+, stdlib; no Android build."""
import argparse
import bisect
import csv
import re
import subprocess
import sys
from pathlib import Path
from xml.parsers import expat

SCOPES = ["src", "res", "lang"] + [
    f"distribution/{variant}/{part}" for variant in ("features", "material3", "github", "fdroid")
    for part in ("src", "res")]
FACTORY = "src/com/mishiranu/dashchan/widget/MotionDialogBuilder.java"
# D1 is the only gate. D2-D5 are prompts for review, including legitimate
# previews, paint/graphics, fallback styles and strings in help resources.
RULES = {
    "D1": re.compile(r"\bnew\s+(?:[\w$]+\s*\.\s*)*(?:MotionDialogBuilder|AlertDialog\s*\.\s*Builder)\s*\("),
    "D2": re.compile(r"\.\s*set(?:CustomTitle|View)\s*\("),
    "D3": re.compile(r"\.\s*setTextSize\s*\(\s*(?:(?:[\w.]+\.)?COMPLEX_UNIT_SP\s*,\s*)?\d+(?:\.\d+)?[fF]?\s*\)"),
    "D4": re.compile(r"\.\s*set(?:BackgroundColor|TextColor|Tint)\s*\(\s*(?:Color\.(?:WHITE|BLACK|GRAY|RED|BLUE)|0x[0-9A-Fa-f]{6,8})\b"),
    "D6": re.compile(r"\bnew\s+(?:[\w$]+\s*\.\s*)*(?:MaterialAlertDialogBuilder|BottomSheetDialog|Dialog|PopupWindow)\s*\("),
}
MEANING = {"D1":"Обход стандартной фабрики", "D2":"Собственное содержимое/заголовок",
           "D3":"Явный размер текста", "D4":"Фиксированный цвет UI", "D5":"Длинная русская строка",
           "D6":"Собственная модальная поверхность"}
LEX = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
SKIP = re.compile(r"^\s*//\s*design-exception\(D1\):\s*(\S.{11,})$")
def supported(p):
    s = p.as_posix()
    return any(s.startswith(d + "/") for d in SCOPES) and p.suffix in (".java", ".kt", ".xml")

def findings(path, raw):
    if path.suffix == ".xml":
        parser = expat.ParserCreate()
        result, buf, meta = [], [], {}
        def start(tag, attrs):
            n = parser.CurrentLineNumber
            if tag == "string" and "values-ru" in path.parent.name:
                meta.update(name=attrs.get("name",""), line=n)
                buf.clear()
            if path.parent.name.startswith("layout"):
                for key, value in attrs.items():
                    code = ""
                    if key == "android:textSize" and re.fullmatch(r"\d+(?:\.\d+)?sp",value): code="D3"
                    if key in {"android:background","android:textColor","android:tint","app:tint"} and value.startswith("#"): code="D4"
                    if code: result.append((code,n,n,key+"="+value,""))
        def end(tag):
            if tag == "string" and meta:
                length=len(" ".join("".join(buf).split()))
                if length >= 240:
                    result.append(("D5",meta["line"],parser.CurrentLineNumber,meta["name"]+" ("+str(length)+")",""))
                meta.clear()
        parser.StartElementHandler, parser.EndElementHandler = start,end
        parser.CharacterDataHandler = lambda s: buf.append(s) if meta else None
        parser.Parse(raw,True)
        return result
    clean = LEX.sub(lambda m:re.sub(r"[^\n]"," ",m[0]),raw)
    lines = raw.splitlines()
    breaks=[m.start() for m in re.finditer("\n",raw)]
    out=[]
    for code,rule in RULES.items():
        if path.suffix == ".kt" and code in ("D1","D6"):
            rule=re.compile(rule.pattern.replace(r"\bnew\s+",r"\b"),rule.flags)
        for m in rule.finditer(clean):
            n=bisect.bisect_left(breaks,m.start())+1
            last=bisect.bisect_left(breaks,m.end()-1)+1
            why=""
            if code=="D1":
                if path.as_posix()==FACTORY and re.match(r"\s*InterfaceAppearance\.standardDialogContext\s*\(",clean[m.end():]): why="shared factory"
                elif n>=2:
                    mark=SKIP.match(lines[n-2])
                    if mark: why=mark[1].strip()
            out.append((code,n,last," ".join(m[0].split()),why))
    return sorted(out,key=lambda x:x[1])

def git(root,*args):
    return subprocess.check_output(["git","-C",str(root),*args])
def delta(root,base):
    git(root,"rev-parse","--verify",base+"^{commit}")
    names=git(root,"diff","--no-renames","--name-only","--diff-filter=AM","-z",base,"--",*SCOPES)
    new=git(root,"ls-files","--others","--exclude-standard","-z","--",*SCOPES)
    out={}
    for b in names.split(b"\0"):
        if not b: continue
        p=Path(b.decode("utf-8"))
        if not supported(p): continue
        diff=git(root,"diff","--no-renames","--no-ext-diff","--no-color","--unified=0",base,"--",p.as_posix())
        out[p]=[(int(s),int(s)+int(c or b"1")-1) for s,c in
                re.findall(rb"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@",diff,re.M) if c!=b"0"]
    for b in new.split(b"\0"):
        if b:
            p=Path(b.decode("utf-8"))
            if supported(p): out[p]=[(1,10**12)]
    return out

def main():
    cmd=argparse.ArgumentParser(description=__doc__)
    cmd.add_argument("mode",choices=["inventory","check"]);cmd.add_argument("--root",type=Path,default=Path("."))
    cmd.add_argument("--base",default="HEAD");cmd.add_argument("--csv",type=Path)
    args=cmd.parse_args();root=args.root.resolve()
    if not (root/"src/com/mishiranu/dashchan/widget/MotionDialogBuilder.java").is_file():
        cmd.error("--root must point to the Dashchan_2 checkout")
    changes=delta(root,args.base) if args.mode=="check" else None
    paths=sorted(changes) if changes is not None else sorted(
        p.relative_to(root) for d in SCOPES for p in (root/d).rglob("*") if p.is_file() and supported(p.relative_to(root)))
    rows=[]; blocked=0
    for p in paths:
        f=root/p
        if f.is_symlink() or not f.is_file():continue
        for code,n,end,detail,why in findings(p,f.read_text("utf-8-sig")):
            if changes is not None and not any(a<=end and b>=n for a,b in changes[p]):continue
            rows.append([code,p.as_posix(),n,MEANING[code],detail,why])
            if changes is not None and code=="D1" and not why:blocked+=1
    if args.csv:
        with args.csv.open("w",encoding="utf-8-sig",newline="") as f:
            w=csv.writer(f);w.writerow(["rule","path","line","review","detail","exception"]);w.writerows(rows)
    else:
        for code,p,n,reason,detail,why in rows:print(f"{code} {p}:{n} {detail} {why}")
    print(f"files={len(paths)} review_signals={len(rows)} blocking_changed_calls={blocked}",file=sys.stderr)
    return int(bool(blocked))
if __name__=="__main__":
    raise SystemExit(main())
