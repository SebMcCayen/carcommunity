"""Integrity-checked official release archives for CI tool caches."""
import hashlib
import os
from pathlib import Path
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, HTTPRedirectHandler, build_opener


class PublicAssetRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlsplit(newurl).scheme != "https":
            raise ValueError("Release redirects must use HTTPS")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None:
            redirected.remove_header("Authorization")
        return redirected


def open_release_asset(request, timeout):
    # Only the fixed official API endpoint may receive the step-scoped token.
    if urlsplit(request.full_url).netloc != "api.github.com" or urlsplit(request.full_url).scheme != "https":
        raise ValueError("Release authentication is restricted to GitHub's HTTPS API")
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_unredirected_header("Authorization", "Bearer " + token)
    return build_opener(PublicAssetRedirect()).open(request, timeout=timeout)


def valid_archive(path, expected_size, expected_sha256):
    if path.is_symlink():
        raise ValueError("Refusing a symlink in the binary archive cache")
    if not path.is_file() or path.stat().st_size != expected_size:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(128 * 1024), b""):
            digest.update(block)
    return digest.hexdigest() == expected_sha256


def prepare_archive(cache_directory, cache_name, api_url, expected_size, expected_sha256, opener=None, sleep=time.sleep):
    opener = opener or open_release_asset
    cache_directory.mkdir(parents=True, exist_ok=True)
    target = cache_directory / cache_name
    if valid_archive(target, expected_size, expected_sha256):
        return target
    for attempt in range(3):
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(prefix=".release-", dir=cache_directory, delete=False) as output:
                temporary = Path(output.name)
                request = Request(api_url, headers={"Accept": "application/octet-stream",
                                                   "User-Agent": "CarCommunity-build"})
                # The transport confines optional CI authentication to the API.
                with opener(request, timeout=60) as response:
                    count = 0
                    while True:
                        block = response.read(128 * 1024)
                        if not block:
                            break
                        count += len(block)
                        if count > expected_size:
                            raise ValueError("Unexpected Release archive size")
                        output.write(block)
            if not valid_archive(temporary, expected_size, expected_sha256):
                raise ValueError("Release archive does not match the pinned manifest checksum")
            os.replace(temporary, target)
            return target
        except (HTTPError, URLError, TimeoutError) as error:
            retryable = not isinstance(error, HTTPError) or error.code in {408, 429, 500, 502, 503, 504}
            if not retryable or attempt == 2:
                raise
            print("Retrying the official Release asset after a network error", flush=True)
            sleep(5 * (attempt + 1))
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    raise RuntimeError("Release preparation did not complete")

