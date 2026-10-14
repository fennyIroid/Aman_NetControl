"""Minimal DNS A-record client over UDP or DNS-over-TLS (what Android Private DNS speaks).

Returns (rcode, [ipv4 answers]) or None on timeout. Standard library only.
"""
import random
import socket
import ssl
import struct


def build(name):
    qid = random.randint(0, 0xFFFF)
    header = struct.pack(">HHHHHH", qid, 0x0100, 1, 0, 0, 0)
    q = b"".join(bytes([len(p)]) + p.encode("idna") for p in name.rstrip(".").split(".")) + b"\0"
    return qid, header + q + struct.pack(">HH", 1, 1)


def skip_name(buf, i):
    while True:
        n = buf[i]
        if n == 0:
            return i + 1
        if n & 0xC0 == 0xC0:
            return i + 2
        i += n + 1


def parse(buf):
    _, flags, qd, an, _, _ = struct.unpack(">HHHHHH", buf[:12])
    rcode = flags & 0xF
    i = 12
    for _ in range(qd):
        i = skip_name(buf, i) + 4
    ips = []
    for _ in range(an):
        i = skip_name(buf, i)
        rtype, _, _, rdlen = struct.unpack(">HHIH", buf[i:i + 10])
        i += 10
        if rtype == 1 and rdlen == 4:
            ips.append(socket.inet_ntoa(buf[i:i + 4]))
        i += rdlen
    return rcode, ips


def udp(server, name, timeout=3.0):
    qid, msg = build(name)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        s.sendto(msg, (server, 53))
        while True:
            data, _ = s.recvfrom(4096)
            if struct.unpack(">H", data[:2])[0] == qid:
                return parse(data)
    except (socket.timeout, OSError):
        return None
    finally:
        s.close()


class DoT:
    """One persistent TLS connection, reused for many queries like Android does."""

    def __init__(self, host, ip=None, timeout=6.0):
        self.host, self.ip, self.timeout = host, ip or host, timeout
        self.sock = None

    def _connect(self):
        ctx = ssl.create_default_context(cafile="/etc/ssl/cert.pem")
        raw = socket.create_connection((self.ip, 853), timeout=self.timeout)
        self.sock = ctx.wrap_socket(raw, server_hostname=self.host)

    def _recv(self, n):
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise OSError("closed")
            out += chunk
        return out

    def query(self, name):
        for _ in range(2):
            try:
                if self.sock is None:
                    self._connect()
                _, msg = build(name)
                self.sock.sendall(struct.pack(">H", len(msg)) + msg)
                ln = struct.unpack(">H", self._recv(2))[0]
                return parse(self._recv(ln))
            except (OSError, ssl.SSLError):
                self.sock = None
        return None


if __name__ == "__main__":
    import sys
    host = sys.argv[1]
    d = DoT(host)
    for n in sys.argv[2:]:
        print(n, d.query(n))
