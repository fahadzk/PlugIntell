"""Send a tiny prompt to OpenAI using OPENAI_API_KEY from the environment.

Install the SDK with: python -m pip install openai
Run with OPENAI_API_KEY set in your shell environment.
"""

import os
import sys

from openai import OpenAI


def main() -> int:
    if not os.environ.get("OPENAI_API_KEY"):
        print("Set OPENAI_API_KEY in your environment, then run this script.", file=sys.stderr)
        return 2

    client = OpenAI()
    response = client.responses.create(
        model="gpt-4o-mini",
        input="Reply with exactly: API test successful",
        max_output_tokens=20,
    )
    print(response.output_text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
