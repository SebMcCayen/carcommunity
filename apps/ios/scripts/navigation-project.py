#!/usr/bin/env python3
"""Generate the real navigation project without changing the secret-free project."""
import argparse
from pathlib import Path
import shutil
import subprocess


def generate(root, lock):
    if not lock.is_file():
        raise ValueError('The committed navigation dependency lock is missing')
    subprocess.run(['xcodegen', 'generate', '--spec', str(root / 'project.navigation.yml')], check=True)
    destination = root / 'KCCNavigation.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved'
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(lock, destination)


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lockfile', type=Path, default=root / 'NavigationSDK/Package.resolved')
    generate(root, parser.parse_args().lockfile)
