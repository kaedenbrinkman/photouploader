"""Bounded HTTP for Google OAuth and Photos Library (timeouts + limited retries)."""
from __future__ import annotations

import logging
import random
import time
from typing import Any

import requests
from google.auth.transport.requests import Request as GoogleAuthRequest
from google.oauth2.credentials import Credentials
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

log = logging.getLogger("gphotos.http")

# (connect, read) seconds — fail fast on hung TLS/handshake.
DEFAULT_TIMEOUT: tuple[float, float] = (15.0, 120.0)
MAX_CALL_ATTEMPTS = 4
MAX_TOKEN_REFRESHES = 2

_NON_RETRYABLE_EXC = (
    requests.exceptions.SSLError,
    requests.exceptions.ConnectionError,
    requests.exceptions.Timeout,
)


class TimeoutGoogleAuthRequest(GoogleAuthRequest):
    """google-auth transport with default connect/read timeouts on every call."""

    def __call__(
        self,
        url,
        method="GET",
        body=None,
        headers=None,
        timeout=None,
        **kwargs,
    ):
        if timeout is None:
            timeout = DEFAULT_TIMEOUT
        return super().__call__(
            url,
            method=method,
            body=body,
            headers=headers,
            timeout=timeout,
            **kwargs,
        )


def build_requests_session() -> requests.Session:
    """Session with limited urllib3 retries (avoid SSL MaxRetryError loops)."""
    # Application layer (photos_api_call) owns retries; avoid urllib3 retry storms on POST.
    retry = Retry(total=0)
    session = requests.Session()
    session.mount("https://", HTTPAdapter(max_retries=retry))
    session.mount("http://", HTTPAdapter(max_retries=retry))
    return session


def google_auth_request(session: requests.Session | None = None) -> TimeoutGoogleAuthRequest:
    return TimeoutGoogleAuthRequest(session=session or build_requests_session())


def refresh_credentials(
    creds: Credentials,
    *,
    session: requests.Session | None = None,
) -> None:
    """Refresh OAuth access token with explicit HTTP timeouts."""
    req = google_auth_request(session)
    creds.refresh(req)
    if not creds.valid:
        raise RuntimeError("Google OAuth token refresh completed but credentials are still invalid")


def photos_api_call(
    credentials: Credentials,
    method: str,
    url: str,
    *,
    session: requests.Session | None = None,
    timeout: tuple[float, float] = DEFAULT_TIMEOUT,
    **kw: Any,
) -> requests.Response:
    """
    Call Google Photos (or upload) APIs with bounded retries and token refresh.
    Does not retry indefinitely on SSL/TLS or connection errors.
    """
    sess = session or build_requests_session()
    auth_req = google_auth_request(sess)
    refreshes = 0
    last_exc: Exception | None = None
    last_status: int | None = None

    for attempt in range(MAX_CALL_ATTEMPTS):
        try:
            if not credentials.valid:
                if refreshes >= MAX_TOKEN_REFRESHES:
                    raise RuntimeError(
                        "Google OAuth token invalid after "
                        f"{MAX_TOKEN_REFRESHES} refresh attempt(s)"
                    )
                log.info("gphotos: refreshing OAuth token (attempt %s)", refreshes + 1)
                credentials.refresh(auth_req)
                refreshes += 1

            headers = dict(kw.pop("headers", {}) or {})
            headers["Authorization"] = f"Bearer {credentials.token}"
            r = sess.request(method, url, headers=headers, timeout=timeout, **kw)
            last_status = r.status_code

            if r.status_code == 429:
                sleep_s = max(5.0, min(60.0, 2**attempt * 3 + random.random()))
                log.warning("gphotos: rate limited %s %s; sleep %.1fs", method, url, sleep_s)
                time.sleep(sleep_s)
                kw["headers"] = headers
                continue
            if r.status_code >= 500:
                sleep_s = min(30.0, 2**attempt + random.random())
                log.warning(
                    "gphotos: server %s on %s %s; sleep %.1fs",
                    r.status_code,
                    method,
                    url,
                    sleep_s,
                )
                time.sleep(sleep_s)
                kw["headers"] = headers
                continue
            if r.status_code == 401:
                if refreshes >= MAX_TOKEN_REFRESHES:
                    r.raise_for_status()
                log.info("gphotos: 401 on %s; refreshing token", url)
                credentials.refresh(auth_req)
                refreshes += 1
                kw["headers"] = headers
                continue

            r.raise_for_status()
            return r
        except _NON_RETRYABLE_EXC as e:
            log.error("gphotos: non-retryable HTTP error %s %s: %s", method, url, e)
            raise RuntimeError(
                f"Google API {method} {url} failed ({type(e).__name__}): {e}"
            ) from e
        except requests.exceptions.HTTPError:
            raise
        except Exception as e:
            last_exc = e
            if attempt + 1 >= MAX_CALL_ATTEMPTS:
                break
            sleep_s = min(15.0, 2**attempt + random.random())
            log.warning("gphotos: attempt %s failed for %s %s: %s", attempt + 1, method, url, e)
            time.sleep(sleep_s)
            kw.setdefault("headers", {})

    detail = last_status if last_status is not None else (last_exc or "unknown")
    raise RuntimeError(f"giving up on {method} {url} after {MAX_CALL_ATTEMPTS} attempts: {detail}")
