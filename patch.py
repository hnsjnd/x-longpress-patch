#!/usr/bin/env python3
"""Apply the "Long press video speed" patch to a decoded X APK (apktool d -r output).
Usage: patch.py <apktool_decoded_dir>
Discovers obfuscated Media3 classes by fingerprints, injects hooks, adds extension smali.
Never ships any X code: it only edits the user's local decode."""
import os, re, subprocess, sys, glob, shutil, tempfile
ROOT = os.path.dirname(os.path.abspath(__file__))
SDK = os.environ.get("ANDROID_SDK", "/workspace/android-sdk")
BT = sorted(glob.glob(f"{SDK}/build-tools/*"))[-1]
ANDROID_JAR = sorted(glob.glob(f"{SDK}/platforms/android-*/android.jar"))[-1]
APKTOOL = os.environ.get("APKTOOL_JAR", os.path.join(ROOT, "tools/apktool.jar"))
JAVAC = os.environ.get("JAVAC", "javac")
d = sys.argv[1]
smalis = glob.glob(f"{d}/smali*/**/*.smali", recursive=True)

def find(pred):
    out = []
    for f in smalis:
        if "/androidx/media3/" not in f: continue
        s = open(f, encoding="utf-8").read()
        if pred(s): out.append((f, s))
    return out

def cls(s): return re.search(r"^\.class.* (L[^;]+;)", s, re.M).group(1)

# 1. ExoPlayerImpl: logs "Init ... [AndroidXMedia3/..." and has a constructor taking the Builder
impl = [x for x in find(lambda s: "AndroidXMedia3/" in s and '"ExoPlayerImpl"' in s and "/exoplayer/" in s)]
assert len(impl) == 1, f"ExoPlayerImpl not unique: {[i[0] for i in impl]}"
impl_f, impl_s = impl[0]; IMPL = cls(impl_s)
# 2. PlaybackParameters: toString "PlaybackParameters(speed="
pp = find(lambda s: "PlaybackParameters(speed=" in s)
assert len(pp) == 1; PP = cls(pp[0][1])
# 3. setPlaybackParameters(PP)V on impl
m = re.findall(r"^\.method public final (\w+)\(" + re.escape(PP) + r"\)V", impl_s, re.M)
assert len(m) == 1, m; SETTER = m[0]
print("ExoPlayerImpl", IMPL, "PlaybackParameters", PP, "setter", SETTER)

# inject registerPlayer before each return-void of the public <init>
ext = "Lapp/longpress/LongPressSpeed;"
def patch_ctor(s):
    mm = re.search(r"(^\.method public constructor <init>\(L[^)]*\)V\n)(.*?)(^\.end method)", s, re.M | re.S)
    body = mm.group(2)
    if "LongPressSpeed" in body: return s
    # find register currently holding `this`: R8 often moves p0 to a low v-register
    mv = re.search(r"move-object/from16 (v\d+), p0\n", body) or re.search(r"move-object (v\d+), p0\n", body)
    this = mv.group(1) if mv else "p0"
    inj = f"    invoke-static/range {{{this} .. {this}}}, {ext}->registerPlayer(Ljava/lang/Object;)V\n\n    return-void"
    body = re.sub(r"    return-void", inj, body)
    return s[:mm.start(2)] + body + s[mm.end(2):]
open(impl_f, "w", encoding="utf-8").write(patch_ctor(impl_s))

# Application.onCreate hook
apk_in = os.environ["APK_IN"]  # original apk, used to read the binary manifest
xt = subprocess.check_output([f"{BT}/aapt", "dump", "xmltree", apk_in, "AndroidManifest.xml"], text=True)
app = re.search(r'E: application.*?A: android:name\(0x01010003\)="([^"]+)"', xt, re.S).group(1)
app_f = [f for f in smalis if f.endswith("/" + app.replace(".", "/") + ".smali")][0]
s = open(app_f, encoding="utf-8").read()
if "LongPressSpeed" not in s:
    s = re.sub(r"(\.method (?:public )?(?:final )?onCreate\(\)V\n\s*\.(?:locals|registers) \d+\n)",
               r"\1\n    invoke-static/range {p0 .. p0}, " + ext + r"->init(Landroid/app/Application;)V\n", s, count=1)
    assert "LongPressSpeed" in s, "onCreate not found"
    open(app_f, "w", encoding="utf-8").write(s)
print("Application", app)

# compile extension with discovered names, dex it, baksmali into a new smali dir
tmp = tempfile.mkdtemp()
src = open(f"{ROOT}/extension/src/app/longpress/LongPressSpeed.java").read()
src = re.sub(r'SET_PARAMS = "[^"]*"', f'SET_PARAMS = "{SETTER}"', src)
src = re.sub(r'PARAMS_CLASS = "[^"]*"', f'PARAMS_CLASS = "{PP[1:-1].replace("/", ".")}"', src)
os.makedirs(f"{tmp}/src/app/longpress"); open(f"{tmp}/src/app/longpress/LongPressSpeed.java", "w").write(src)
subprocess.check_call([JAVAC, "--release", "11", "-cp", ANDROID_JAR, "-d", f"{tmp}/cls", f"{tmp}/src/app/longpress/LongPressSpeed.java"])
classes = glob.glob(f"{tmp}/cls/**/*.class", recursive=True)
subprocess.check_call([f"{BT}/d8", "--min-api", "29", "--lib", ANDROID_JAR, "--output", tmp] + classes)
n = len(glob.glob(f"{d}/smali*")) + 1
out = os.environ.get("EXT_DEX_OUT", f"{d}/../classes{n}.dex")
shutil.copy(f"{tmp}/classes.dex", out)
open(os.path.join(os.path.dirname(out), "ext_dex_name"), "w").write(f"classes{n}.dex")
print("extension dex ->", out, "as", f"classes{n}.dex")

# optional: bump versionCode in the raw binary manifest so a rebuilt patch upgrades over the previous one
bump = int(os.environ.get("VERSION_CODE_BUMP", "0"))
if bump:
    import struct
    vc = int(re.search(r"versionCode='(\d+)'", subprocess.check_output([f"{BT}/aapt", "dump", "badging", apk_in], text=True)).group(1))
    mp = f"{d}/AndroidManifest.xml"; b = open(mp, "rb").read(); o = struct.pack("<I", vc)
    assert b.count(o) == 1, "versionCode not uniquely found"
    open(mp, "wb").write(b.replace(o, struct.pack("<I", vc + bump))); print("versionCode", vc, "->", vc + bump)
