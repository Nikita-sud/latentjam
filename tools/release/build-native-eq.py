#!/usr/bin/env python3
# Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0
"""Build the pinned shared EQ for host JNI or an Apple static library. No downloads."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--platform', choices=['host', 'iosArm64', 'iosSimulatorArm64'], required=True)
    p.add_argument('--source-dir', type=Path, required=True)
    p.add_argument('--output-dir', type=Path, required=True)
    p.add_argument('--java-home', type=Path)
    args = p.parse_args()
    source = args.source_dir.resolve(); output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    flags = ['-std=c++17', '-O3', '-ffp-contract=off', '-fno-fast-math', '-fPIC', '-I'+str(source), '-I'+str(source/'vendor/libebur128/queue')]
    if args.platform == 'host':
        compiler = shutil.which('clang++') or shutil.which('g++')
        if not compiler or not args.java_home:
            p.error('Host JNI build needs a C++17 compiler and --java-home')
        suffix = 'dylib' if sys.platform == 'darwin' else 'so'
        include_os = 'darwin' if sys.platform == 'darwin' else 'linux'
        run(shutil.which('clang') or shutil.which('gcc'), '-std=gnu99', '-O3', '-ffp-contract=off', '-fno-fast-math', '-fPIC',
            '-I'+str(source/'vendor/libebur128/queue'), '-c', source/'vendor/libebur128/ebur128.c', '-o', output/'ebur128.o')
        run(compiler, *flags, '-I'+str(args.java_home/'include'), '-I'+str(args.java_home/'include'/include_os),
            '-shared', source/'lj_equalizer.cpp', source/'lj_equalizer_jni.cpp', source/'lj_loudness.cpp',
            source/'lj_loudness_jni.cpp', output/'ebur128.o', '-o', output/f'liblj_equalizer.{suffix}')
    else:
        sdk = 'iphoneos' if args.platform == 'iosArm64' else 'iphonesimulator'
        sdk_path = subprocess.check_output(['xcrun', '--sdk', sdk, '--show-sdk-path'], text=True).strip()
        target = 'arm64-apple-ios15.0' + ('-simulator' if args.platform == 'iosSimulatorArm64' else '')
        c_obj = output/'ebur128.o'
        run('xcrun', '--sdk', sdk, 'clang', '-std=gnu99', '-O3', '-ffp-contract=off', '-fno-fast-math', '-fPIC', '-target', target,
            '-isysroot', sdk_path, '-I'+str(source/'vendor/libebur128/queue'),
            '-c', source/'vendor/libebur128/ebur128.c', '-o', c_obj)
        objects = [c_obj]
        for filename in ['lj_equalizer.cpp', 'lj_loudness.cpp', 'lj_equalizer_ios.mm']:
            obj = output/(filename+'.o'); objects.append(obj)
            run('xcrun', '--sdk', sdk, 'clang++', *flags, '-target', target, '-isysroot', sdk_path,
                *(['-fobjc-arc'] if filename.endswith('.mm') else []), '-c', source/filename, '-o', obj)
        run('xcrun', 'ar', 'rcs', output/'liblj_equalizer.a', *objects)


if __name__ == '__main__':
    main()
