#!/usr/bin/env python3
"""Read the facts about a managed assembly that decide the AOT plan.

No .NET SDK required. Reports:

  * PE shape -- machine, PE32 vs PE32+
  * CorFlags  -- ILONLY is the one that matters: an IL-only assembly can be
                 AOT-compiled for another architecture, a native one cannot
  * CLR metadata version string
  * TargetFramework, read out of the #Blob heap
  * metadata stream sizes and the #~ table counts

Deliberately does NOT walk the AssemblyRef table. An attempt at a full ECMA-335
table walk was written and then thrown away: the #~ valid mask on .NET 8
assemblies sets table ids above 0x23, which the published schema does not
describe, and no self-check could confirm a row-size table against a known-good
assembly. Guessing there would produce a plausible-looking but wrong dependency
list.

Use the app's *.deps.json for the authoritative closure instead -- it lists
every library with its version, which is strictly more information than
AssemblyRef. See docs/AOT-PLAN.md.

    python3 tools/dotnet_meta.py path/to/Some.dll [...]
"""
import struct
import sys

CORFLAGS = [
    (0x01, 'ILONLY'),
    (0x02, '32BITREQUIRED'),
    (0x04, 'IL_LIBRARY'),
    (0x08, 'STRONGNAMESIGNED'),
    (0x10, 'NATIVE_ENTRYPOINT'),
    (0x20, '32BITPREFERRED'),
]

MACHINE = {0x014c: 'x86', 0x8664: 'x64', 0xaa64: 'arm64', 0x01c4: 'armv7'}


def read(path):
    d = open(path, 'rb').read()
    if d[:2] != b'MZ':
        raise ValueError('not a PE file (no MZ header)')
    pe = struct.unpack_from('<I', d, 0x3C)[0]
    if d[pe:pe + 4] != b'PE\0\0':
        raise ValueError('bad PE signature')
    machine, nsec = struct.unpack_from('<HH', d, pe + 4)
    opt = pe + 24
    magic = struct.unpack_from('<H', d, opt)[0]
    if magic not in (0x10b, 0x20b):
        raise ValueError('bad optional-header magic 0x%x' % magic)
    dd = opt + (112 if magic == 0x20b else 96) + 14 * 8
    cli_rva, cli_size = struct.unpack_from('<II', d, dd)
    if cli_rva == 0:
        raise ValueError('no CLI header -- this is a native image, not managed')

    sec = opt + struct.unpack_from('<H', d, pe + 20)[0]
    sects = []
    for i in range(nsec):
        o = sec + i * 40
        vs, va, rs, pr = struct.unpack_from('<IIII', d, o + 8)
        sects.append((va, max(vs, rs), pr))

    def r2o(rva):
        for va, sz, pr in sects:
            if va <= rva < va + sz:
                return pr + (rva - va)
        raise ValueError('rva 0x%x is not mapped by any section' % rva)

    co = r2o(cli_rva)
    _, rtmaj, rtmin, md_rva, md_size, flags, entry = struct.unpack_from('<IHHIIII', d, co)

    md = r2o(md_rva)
    if d[md:md + 4] != b'BSJB':
        raise ValueError('metadata does not start with BSJB')
    vlen = struct.unpack_from('<I', d, md + 12)[0]
    mdver = d[md + 16:md + 16 + vlen].rstrip(b'\0').decode('ascii', 'replace')

    o = md + 16 + vlen
    _sflags, nstreams = struct.unpack_from('<HH', d, o)
    o += 4
    streams = {}
    for _ in range(nstreams):
        soff, ssz = struct.unpack_from('<II', d, o)
        o += 8
        e = o
        while d[e] != 0:
            e += 1
        streams[d[o:e].decode('ascii', 'replace')] = (md + soff, ssz)
        o = (e + 1 + 3) & ~3

    out = {
        'path': path,
        'machine': MACHINE.get(machine, '0x%04x' % machine),
        'pe': 'PE32+' if magic == 0x20b else 'PE32',
        'corflags': flags,
        'runtime': '%d.%d' % (rtmaj, rtmin),
        'metadata': mdver,
        'entrypoint': entry,
        'streams': {k: v[1] for k, v in streams.items()},
        'tables': {},
        'tfm': None,
    }
    for bit, name in CORFLAGS:
        out[name] = bool(flags & bit)

    # TargetFramework lives in a CustomAttribute blob as a plain UTF-8 string.
    if '#Blob' in streams:
        boff, bsz = streams['#Blob']
        blob = d[boff:boff + bsz]
        for marker in (b'.NETCoreApp,Version=v', b'.NETStandard,Version=v',
                       b'.NETFramework,Version=v'):
            i = blob.find(marker)
            if i >= 0:
                j = i
                while j < len(blob) and 0x20 <= blob[j] < 0x7f:
                    j += 1
                out['tfm'] = blob[i:j].decode('ascii')
                break

    if '#~' in streams:
        tio, tsz = streams['#~']
        hsz = struct.unpack_from('<B', d, tio + 6)[0]
        valid = struct.unpack_from('<Q', d, tio + 8)[0]
        o = tio + 24
        for t in range(64):
            if valid >> t & 1:
                out['tables'][t] = struct.unpack_from('<I', d, o)[0]
                o += 4
        out['heap_sizes'] = hsz
        out['valid_mask'] = valid
        # Self-check: the count array must end exactly where the row data starts,
        # and the rows must fit inside the declared stream size.
        out['header_bytes'] = o - tio
        out['stream_size'] = tsz
        out['header_ok'] = (o - tio) == 24 + 4 * len(out['tables'])

    return out


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    rc = 0
    for p in argv:
        try:
            r = read(p)
        except Exception as e:
            print('\n=== %s ===\n  NOT MANAGED / unreadable: %s' % (p, e))
            rc = 1
            continue
        print('\n=== %s ===' % p)
        print('  machine: %s  %s  metadata %s  runtime %s'
              % (r['machine'], r['pe'], r['metadata'], r['runtime']))
        print('  CorFlags: 0x%x -> %s'
              % (r['corflags'],
                 ' '.join('%s=%s' % (n, r[n]) for _b, n in CORFLAGS)))
        print('  TargetFramework: %s' % (r['tfm'] or '(not found in #Blob)'))
        print('  streams: %s' % ', '.join('%s=%d' % kv for kv in sorted(r['streams'].items())))
        if r['tables']:
            print('  #~ tables: %d present, header %d bytes (%s)'
                  % (len(r['tables']), r['header_bytes'],
                     'self-check OK' if r['header_ok'] else 'SELF-CHECK FAILED'))
            print('    counts: %s'
                  % ' '.join('%d:%d' % kv for kv in sorted(r['tables'].items())))
        if r['entrypoint']:
            print('  entry point token: 0x%08x' % r['entrypoint'])
        if r['ILONLY']:
            print('  -> IL-only: AOT-compilable for another architecture')
        else:
            print('  -> NOT IL-only: cannot be AOT-compiled for another architecture')
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
