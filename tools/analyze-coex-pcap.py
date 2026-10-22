#!/usr/bin/env python3
"""Summarize TCP sequence observations in Ethernet/IPv6 classic pcaps.

Repeated bytes are observations at the capture point, not proof of network
retransmission. Missing sequence ranges can also reflect capture loss.
"""
import argparse
from collections import defaultdict
import ipaddress
import json
import struct


def packets(path):
    with open(path, 'rb') as stream:
        header = stream.read(24)
        formats = {b'\xd4\xc3\xb2\xa1': ('<', 1e6),
                   b'\xa1\xb2\xc3\xd4': ('>', 1e6),
                   b'\x4d\x3c\xb2\xa1': ('<', 1e9),
                   b'\xa1\xb2\x3c\x4d': ('>', 1e9)}
        if len(header) != 24 or header[:4] not in formats:
            raise ValueError('Expected a classic pcap')
        endian, scale = formats[header[:4]]
        link = struct.unpack(endian + 'I', header[20:24])[0]
        if link != 1:
            raise ValueError(f'Expected Ethernet link type 1, got {link}')
        while raw := stream.read(16):
            if len(raw) != 16:
                raise ValueError('Truncated packet record')
            sec, usec, size, original = struct.unpack(endian + 'IIII', raw)
            if size > 16 * 1024 * 1024:
                raise ValueError('Invalid packet size')
            data = stream.read(size)
            if len(data) != size:
                raise ValueError('Truncated packet contents')
            if len(data) < 74 or data[12:14] != b'\x86\xdd':
                continue
            # AWDL tun packets in these captures have no IPv6 extensions.
            if data[20] != 6:
                continue
            length = struct.unpack('!H', data[18:20])[0]
            tcp = data[54:]
            sport, dport, seq, ack = struct.unpack('!HHII', tcp[:12])
            hlen = (tcp[12] >> 4) * 4
            if hlen < 20 or length < hlen:
                continue
            yield dict(time=sec + usec / scale,
                       src=str(ipaddress.IPv6Address(data[22:38])),
                       dst=str(ipaddress.IPv6Address(data[38:54])),
                       sport=sport, dport=dport, seq=seq, ack=ack,
                       flags=tcp[13], window=struct.unpack('!H', tcp[14:16])[0],
                       payload=length-hlen, options=tcp[20:hlen])


def summarize(path):
    flows = defaultdict(list)
    for packet in packets(path):
        key = (packet['src'], packet['sport'], packet['dst'], packet['dport'])
        flows[key].append(packet)
    result = []
    for key, items in flows.items():
        data = [p for p in items if p['payload']]
        intervals = []
        overlap = 0
        out_of_order = 0
        high = 0
        # Each experiment is < 32 MiB, so modular offsets do not wrap twice.
        origin = next((p['seq'] + 1 for p in items if p['flags'] & 2),
                      data[0]['seq'] if data else 0)
        for p in data:
            start = (p['seq']-origin) & 0xffffffff
            end = start + p['payload']
            if start < high:
                out_of_order += 1
            high = max(high, end)
            overlap += sum(max(0, min(end, b)-max(start, a))
                           for a, b in intervals)
            merged = []
            for a, b in sorted(intervals + [(start, end)]):
                if merged and a <= merged[-1][1]:
                    merged[-1] = (merged[-1][0], max(b, merged[-1][1]))
                else:
                    merged.append((a, b))
            intervals = merged
        duration = data[-1]['time']-data[0]['time'] if len(data) > 1 else 0
        bins = defaultdict(int)
        for p in data:
            bins[int(p['time']-data[0]['time'])] += p['payload']
        result.append(dict(src=key[0], sport=key[1], dst=key[2], dport=key[3],
                           packets=len(items), data_packets=len(data),
                           observed_payload=sum(p['payload'] for p in data),
                           unique_payload=sum(b-a for a, b in intervals),
                           overlapping_payload=overlap,
                           packets_below_highest_sequence=out_of_order,
                           zero_window_packets=sum(p['window'] == 0 for p in items),
                           data_duration_s=round(duration, 6),
                           largest_data_gap_s=round(max((b['time']-a['time']
                               for a, b in zip(data, data[1:])), default=0), 6),
                           payload_by_second=dict(sorted(bins.items()))))
    return sorted(result, key=lambda f: f['observed_payload'], reverse=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pcap')
    args = parser.parse_args()
    print(json.dumps(summarize(args.pcap), indent=2))
