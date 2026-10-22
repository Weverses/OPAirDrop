#!/usr/bin/env python3
"""Apply a bounded peer-expiry grace to the exact observed Mosey FFI build.

NDK llvm-objdump evidence (ELF VA == file offset for the executable LOAD):
  0x1e532c: mov w1, #96
  0x1e5348: bl 0x1f7b60   (u16 availability period * multiplier, saturating)
  0x1e534c: bl 0x1f9d6c   (TU -> Duration, 1024 us per TU)
  0x1e550c/10: store Duration at state+0x240/+0x248
  0x1e4d70/8c: peer sweep loads precisely that Duration for expiry.

Only the expiry multiplier changes (96 -> 384). AW scheduling, channel
selection, frame parsing, TCP timers and the expiry branch remain unchanged.
At the observed availability period 16 TU: 1.572864 -> 6.291456 seconds.
The grace remains bounded and preserves real disconnection cleanup.
"""
from pathlib import Path
import argparse
import hashlib
import json
import struct

SOURCE_SHA256 = '7de30b96a0a413eb6348860e76fb2032edf7f350658857bea3c2862b666dd9e7'
PATCH_OFFSET = 0x1e532c
OLD_INSTRUCTION = 0x52800c01
NEW_INSTRUCTION = 0x52803001
GUARDS = {0x1e5348: 0x94004a06, 0x1e534c: 0x94005288,
          0x1e550c: 0xf9012299, 0x1e5510: 0xb9024a9a,
          0x1e4d70: 0xf9412275, 0x1e4d8c: 0xb9424a78}


def patch(data):
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError('Unsupported Mosey FFI SHA256; no output written')
    for offset, expected in {PATCH_OFFSET: OLD_INSTRUCTION, **GUARDS}.items():
        if struct.unpack_from('<I', data, offset)[0] != expected:
            raise ValueError(f'Unexpected instruction at {offset:#x}')
    output = bytearray(data)
    struct.pack_into('<I', output, PATCH_OFFSET, NEW_INSTRUCTION)
    return bytes(output)


def write_patch(source, target):
    original = source.read_bytes()
    modified = patch(original)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(modified)
    record = {'input_sha256': SOURCE_SHA256,
              'output_sha256': hashlib.sha256(modified).hexdigest(),
              'file_offset': PATCH_OFFSET, 'old_instruction': f'{OLD_INSTRUCTION:08x}',
              'new_instruction': f'{NEW_INSTRUCTION:08x}',
              'availability_period_multiplier': {'old': 96, 'new': 384},
              'observed_period_tu': 16, 'old_grace_s': 1.572864, 'new_grace_s': 6.291456,
              'validation': 'exact input hash, instruction guards, one instruction changed',
              'runtime_validation': 'required'}
    target.with_suffix(target.suffix + '.peer-grace.json').write_text(json.dumps(record, indent=2) + '\n')
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('target', type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.target.resolve():
        parser.error('Use a separate output path')
    print(json.dumps(write_patch(args.source, args.target), indent=2))


if __name__ == '__main__':
    main()
