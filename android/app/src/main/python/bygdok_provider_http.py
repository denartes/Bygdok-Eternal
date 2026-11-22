from types import SimpleNamespace

import httpx


class APIStatusError(Exception):
    def __init__(self, response, body):
        self.response = response
        self.status_code = response.status_code
        self.body = body
        super().__init__("Provider request failed with HTTP %s" % self.status_code)


def to_namespace(value):
    if isinstance(value, dict):
        return SimpleNamespace(**{
            key: to_namespace(item) for key, item in value.items()
        })
    if isinstance(value, list):
        return [to_namespace(item) for item in value]
    return value


def post_json(url, headers, payload, timeout):
    response = httpx.post(
        url,
        headers=headers,
        json=payload,
        timeout=timeout,
    )
    try:
        body = response.json()
    except ValueError as error:
        if not response.is_success:
            body = {"error": {"message": "Provider returned a non-JSON error"}}
        else:
            raise ValueError("Provider returned invalid JSON") from error
    if not response.is_success:
        raise APIStatusError(response, body)
    if not isinstance(body, dict):
        raise ValueError("Provider returned an unexpected JSON response")
    return to_namespace(body)
