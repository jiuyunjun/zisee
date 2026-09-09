"""Build the pinned local DeepFilterNet runtime (Linux/macOS or Windows through WSL).

Requires Rust 1.98.1, Android NDK r27d, Python 3. No SDK secrets or model downloads at runtime.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
REV = "ee6505ab4e899d3bef734427e627a266b6fef441"
ARCHIVE_SHA256 = "75c67bf1839c02fabbbc9de73e58508c31d2a7b646f2dd9e749b2be2b7d2e964"
BUILD = Path(os.environ.get("ZISEE_AUDIO_BUILD", str(ROOT / "build")))
UPSTREAM = BUILD / "upstream"
BRIDGE = BUILD / "bridge"
NDK = Path(os.environ["ANDROID_NDK_HOME"])
HOST = "darwin-x86_64" if os.uname().sysname == "Darwin" else "linux-x86_64"
BIN = NDK / "toolchains/llvm/prebuilt" / HOST / "bin"
TOOLCHAIN = "1.98.1"

BUILD.mkdir(parents=True, exist_ok=True)
if not UPSTREAM.exists():
    archive = BUILD / "upstream.tar.gz"
    urllib.request.urlretrieve(f"https://codeload.github.com/KaleyraVideo/DeepFilterNet/tar.gz/{REV}", archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != ARCHIVE_SHA256:
        raise RuntimeError("DeepFilterNet source checksum mismatch")
    with tarfile.open(archive) as bundle:
        bundle.extractall(BUILD, filter="data")
    (BUILD / f"DeepFilterNet-{REV}").rename(UPSTREAM)
    # Resolve only the DSP crate, not unrelated Python/demo/HDF5 workspace members.
    (UPSTREAM / "Cargo.toml").write_text('[workspace]\nresolver = "2"\nmembers = ["libDF"]\n', encoding="utf-8")

BRIDGE.mkdir(exist_ok=True)
shutil.copy2(ROOT / "Cargo.toml", BRIDGE / "Cargo.toml")
shutil.copytree(ROOT / "src", BRIDGE / "src", dirs_exist_ok=True)
if (ROOT / "Cargo.lock").exists():
    shutil.copy2(ROOT / "Cargo.lock", BRIDGE / "Cargo.lock")

targets = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android26-clang"),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android26-clang"),
}
for abi in os.environ.get("ZISEE_AUDIO_ABIS", "arm64-v8a,x86_64").split(","):
    target, clang = targets[abi]
    subprocess.run(["rustup", "target", "add", "--toolchain", TOOLCHAIN, target], check=True)
    env = os.environ.copy()
    env[f"CARGO_TARGET_{target.upper().replace('-', '_')}_LINKER"] = str(BIN / clang)
    env[f"CC_{target.replace('-', '_')}"] = str(BIN / clang)
    env[f"AR_{target.replace('-', '_')}"] = str(BIN / "llvm-ar")
    env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
    command = ["cargo", f"+{TOOLCHAIN}", "build", "--release", "--target", target, "--jobs", "4"]
    if (ROOT / "Cargo.lock").exists():
        command.append("--locked")
    subprocess.run(command, cwd=BRIDGE, env=env, check=True)
    output = ROOT / "../app/src/main/jniLibs" / abi
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(BRIDGE / "target" / target / "release/libzisee_audio.so", output)
shutil.copy2(BRIDGE / "Cargo.lock", ROOT / "Cargo.lock")
assets = ROOT / "../app/src/main/assets/audio"
assets.mkdir(parents=True, exist_ok=True)
model = UPSTREAM / "models/DeepFilterNet3_onnx_mobile.tar.gz"
if hashlib.sha256(model.read_bytes()).hexdigest() != "5600b6857117ecc7cf460b8ec4841963bfa6d718921d424d42dea5d3d37a8c32":
    raise RuntimeError("DeepFilterNet model checksum mismatch")
shutil.copy2(model, assets / "deepfilter-mobile.model")
print("model SHA256", hashlib.sha256(model.read_bytes()).hexdigest())
for name in ("LICENSE-MIT", "LICENSE-APACHE"):
    shutil.copy2(UPSTREAM / name, ROOT / name)

# Include upstream/transitive copyright and license texts in the distributed APK.
metadata = json.loads(subprocess.check_output(["cargo", f"+{TOOLCHAIN}", "metadata", "--format-version", "1", "--locked"], cwd=BRIDGE))
notices = ["Zisee audio third-party notices\n", f"DeepFilterNet source: {REV}\n",
           (ROOT / "LICENSE-MIT").read_text(encoding="utf-8"), (ROOT / "LICENSE-APACHE").read_text(encoding="utf-8")]
for package in sorted(metadata["packages"], key=lambda item: item["name"]):
    if package["name"] == "zisee_audio":
        continue
    notices.append(f"\n{package['name']} {package['version']} — {package.get('license')}\n{package.get('repository')}\n")
    directory = Path(package["manifest_path"]).parent
    for file in sorted(directory.iterdir()):
        if file.is_file() and file.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")):
            notices.append(file.read_text(encoding="utf-8"))
(assets / "THIRD_PARTY_NOTICES.txt").write_text("\n".join(notices), encoding="utf-8")
files = [ROOT / "Cargo.toml", ROOT / "Cargo.lock", ROOT / "src/lib.rs", ROOT / "build.py",
         assets / "deepfilter-mobile.model", assets / "THIRD_PARTY_NOTICES.txt"]
files += sorted((ROOT / "../app/src/main/jniLibs").glob("*/libzisee_audio.so"))
checksums = [f"# Rust {TOOLCHAIN}, NDK r27d, DeepFilterNet {REV}"]
for file in files:
    checksums.append(f"{file.resolve().relative_to(ROOT.parent).as_posix()}={hashlib.sha256(file.read_bytes()).hexdigest()}")
(ROOT / "artifacts.properties").write_text("\n".join(checksums) + "\n", encoding="utf-8")
