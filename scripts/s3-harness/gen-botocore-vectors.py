"""Writes SigV4 vectors from botocore, an independent implementation, for
scripts/s3-harness (S3Harness reads fixtures/botocore-vectors.tsv).

  python3 -I scripts/s3-harness/gen-botocore-vectors.py > scripts/s3-harness/fixtures/botocore-vectors.tsv

Each line: method, key, query (JSON list of [name, value], no repeated
names: botocore's params are a dict), payload hex, the encoded path, the
canonical query and the signature botocore computes for a request signing
host + x-amz-content-sha256 + x-amz-date. The path is encoded here with
urllib's quote(safe='/~'), which is AWS's UriEncode with the slash kept;
botocore's S3 signer takes the path as sent, so the encoding is checked
against Python's and the signature against botocore's.
"""
import hashlib
import json
import random
import sys
from urllib.parse import quote

from botocore.auth import S3SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials

random.seed(20261018)
CREDS = Credentials("AKIDEXAMPLE12345", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
ALPHABET = ("abcXYZ0129-._~ /+=&?#%!$'()*,;:@[]\"<>^`{|}" + "éü中\U0001f600")


def rand_text(n):
    return "".join(random.choice(ALPHABET) for _ in range(n))


def case(method, key, query, payload, host, region, amz_date):
    path = "/" + quote(key.encode("utf-8"), safe="/~")
    url = "https://" + host + path
    req = AWSRequest(method=method, url=url, params=dict(query), data=payload)
    sha = hashlib.sha256(payload).hexdigest()
    req.headers["host"] = host
    req.headers["x-amz-content-sha256"] = sha
    req.headers["x-amz-date"] = amz_date
    req.context["timestamp"] = amz_date
    auth = S3SigV4Auth(CREDS, "s3", region)
    # botocore stamps X-Amz-Date from its own clock; pin it.
    auth._modify_request_before_signing = lambda r: None
    canonical = auth.canonical_request(req)
    string_to_sign = auth.string_to_sign(req, canonical)
    signature = auth.signature(string_to_sign, req)
    cq = canonical.split("\n")[2]
    print("\t".join([method, json.dumps(key), json.dumps(query), payload.hex(), path, cq,
                     signature, host, region, amz_date]))


fixed = [
    ("GET", "Firedown Backup/ABCDEF/objects/0123456789abcdef0123456789abcdef.entry", [], b""),
    ("PUT", "phone/Firedown Backup/ABCDEF/objects/0123456789abcdef0123456789abcdef.fdvc",
     [["partNumber", "3"], ["uploadId", "2~abc/def+ghi=="]], b"\x00\x01\x02 part bytes"),
    ("GET", "", [["list-type", "2"], ["prefix", "Firedown Backup/ABC/objects/"],
                 ["max-keys", "1000"], ["continuation-token", "1/aBc+dE=f"]], b""),
    ("POST", "", [["delete", ""]], b"<Delete><Quiet>true</Quiet></Delete>"),
    ("POST", "k", [["uploads", ""]], b""),
    ("DELETE", "a b/c~d", [["uploadId", "x y"]], b""),
]
for method, key, query, payload in fixed:
    case(method, key, query, payload, "s3.us-west-004.backblazeb2.com", "us-west-004",
         "20261018T101112Z")
for i in range(60):
    method = random.choice(["GET", "PUT", "POST", "DELETE", "HEAD"])
    key = rand_text(random.randint(1, 40))
    names = set()
    query = []
    for _ in range(random.randint(0, 4)):
        name = rand_text(random.randint(1, 8))
        if name in names:
            continue
        names.add(name)
        query.append([name, rand_text(random.randint(0, 12))])
    payload = bytes(random.getrandbits(8) for _ in range(random.randint(0, 64)))
    host = random.choice(["localhost:9000", "bucket.example.r2.cloudflarestorage.com",
                          "s3.eu-central-003.backblazeb2.com", "[::1]:9000"])
    region = random.choice(["us-east-1", "auto", "eu-central-003", "fsn1"])
    case(method, key, query, payload, host, region, "2026%02d%02dT%02d%02d%02dZ" % (
        random.randint(1, 12), random.randint(1, 28), random.randint(0, 23),
        random.randint(0, 59), random.randint(0, 59)))
