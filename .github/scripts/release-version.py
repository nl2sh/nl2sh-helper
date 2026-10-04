"""Resolve strict release tags using the same version-code scheme as ascrcpy."""
import os
import re

match = re.fullmatch(r"v(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", os.environ["RELEASE_TAG"])
if not match:
    raise SystemExit("Expected vMAJOR.MINOR.PATCH[-suffix]; minor/patch must be below 100, without leading zeros")
major, minor, patch = map(int, match.group(1, 2, 3))
code = major * 10000 + minor * 100 + patch
if major >= 210000 or code < 1:
    raise SystemExit("Version must have major < 210000 and a positive versionCode")
print(f"version={match.group(0)[1:]}")
print(f"code={code}")
print(f"prerelease={str(bool(match.group(4))).lower()}")
