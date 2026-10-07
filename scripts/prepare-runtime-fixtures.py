#!/usr/bin/env python3
"""Prepare valid Android ELF fixtures for the disposable-emulator lifecycle test."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--runtime', required=True, type=Path, help='Real x86_64 Android release binary')
args = parser.parse_args()
ndk = os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT')
if not ndk:
    parser.error('Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT')
compilers = list(Path(ndk).glob('toolchains/llvm/prebuilt/*/bin/x86_64-linux-android26-clang'))
compilers = [path for path in compilers if path.is_file() and path.suffix != '.cmd']
if len(compilers) != 1:
    parser.error('Cannot locate the x86_64 API 26 NDK compiler')
with args.runtime.open('rb') as runtime:
    header = runtime.read(20)
if len(header) != 20 or header[:6] != b'\x7fELF\x02\x01' or int.from_bytes(header[18:20], 'little') != 62:
    parser.error('The runtime must be a native x86_64 little-endian Android ELF')
output = Path(__file__).resolve().parent.parent / 'app/build/runtime-fixtures'
output.mkdir(parents=True, exist_ok=True)
shutil.copyfile(args.runtime, output / 'good-runtime')
source = r'''fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|v| v == "--version") { println!("nl2sh 9.9.9"); }
    else if args.iter().any(|v| v == "--help") { println!("Commands:\n  service  Native service control"); }
    else { eprintln!("Intentional failed runtime startup fixture"); std::process::exit(42); }
}'''
with tempfile.TemporaryDirectory(prefix='nl2sh-fixture-') as directory:
    rust = Path(directory) / 'bad_runtime.rs'
    rust.write_text(source)
    subprocess.run(['rustc', str(rust), '--target', 'x86_64-linux-android', '-C',
                    f'linker={compilers[0]}', '-C', 'opt-level=z', '-C', 'strip=symbols',
                    '-o', str(output / 'bad-runtime')], check=True)
print('Prepared good-runtime and a deliberately failing Android ELF in app/build/runtime-fixtures')
