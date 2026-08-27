#!/usr/bin/env python3
"""Classify a Vintage Story install for the Mono AOT step.

Walks a game directory and buckets every file:

  managed   a PE with a CLI header  -> needs an AOT image
  native    an ELF shared object     -> runtime dependency, not AOT-able here
  other     pdb/xml/json/desktop/sh/apphost/...

The apphost executables (Vintagestory, VintagestoryServer, ModMaker,
VSCrashReporter) are ELF binaries built for linux-x64. They are irrelevant on
Android -- the launcher starts the runtime itself through libhostfxr.

    python3 tools/aot/classify.py <game-dir> [--trim]

--trim deletes everything not in the "managed" bucket. Pass --dry-run with it
to see what would go without touching anything.
"""
import os
import struct
import sys


def classify(path):
    """Return 'managed', 'native', or 'other'."""
    try:
        with open(path, 'rb') as f:
            head = f.read(2)
            if head == b'MZ':
                f.seek(0x3C)
                pe_off = struct.unpack('<I', f.read(4))[0]
                f.seek(pe_off)
                if f.read(4) != b'PE\0\0':
                    return 'other'
                f.seek(pe_off + 24)
                magic = struct.unpack('<H', f.read(2))[0]
                if magic not in (0x10b, 0x20b):
                    return 'other'
                # CLI header is data directory 14
                f.seek(pe_off + 24 + (112 if magic == 0x20b else 96) + 14 * 8)
                rva = struct.unpack('<I', f.read(4))[0]
                return 'managed' if rva else 'other'
            if head == b'\x7fE':
                f.seek(0)
                if f.read(4) == b'\x7fELF':
                    return 'native'
    except (OSError, struct.error):
        pass
    return 'other'


def scan(root):
    buckets = {'managed': [], 'native': [], 'other': []}
    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            p = os.path.join(dirpath, name)
            buckets[classify(p)].append(p)
    for v in buckets.values():
        v.sort()
    return buckets


def main(argv):
    args = [a for a in argv[1:] if not a.startswith('--')]
    flags = {a for a in argv[1:] if a.startswith('--')}
    if not args:
        print(__doc__)
        return 2
    root = args[0]
    b = scan(root)

    def size(paths):
        return sum(os.path.getsize(p) for p in paths)

    print('scan of %s\n' % root)
    for kind in ('managed', 'native', 'other'):
        print('  %-8s %3d files  %8.1f MB' % (kind, len(b[kind]), size(b[kind]) / 1048576))
    print()
    print('managed (these get AOT images):')
    for p in b['managed']:
        print('   %s' % os.path.relpath(p, root))
    print()
    print('native (needed at runtime, NOT AOT-able here):')
    for p in b['native']:
        print('   %s' % os.path.relpath(p, root))
    print()
    print('other (not needed for AOT):')
    for p in b['other']:
        print('   %s  (%.0f KB)' % (os.path.relpath(p, root), os.path.getsize(p) / 1024))

    if '--trim' in flags:
        doomed = b['native'] + b['other'] if '--keep-native' not in flags else b['other']
        if '--dry-run' in flags:
            print('\nDRY RUN -- would delete %d files, %.1f MB'
                  % (len(doomed), size(doomed) / 1048576))
        else:
            for p in doomed:
                os.remove(p)
            print('\ndeleted %d files, freed %.1f MB'
                  % (len(doomed), size(doomed) / 1048576))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
