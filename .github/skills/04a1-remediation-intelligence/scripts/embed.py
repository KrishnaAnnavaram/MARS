#!/usr/bin/env python
"""
04a1 -- local sentence-embedding worker.

Reads a JSON array of strings from stdin, encodes them with a local sentence-transformers
model (no network call at run time beyond the one-time model download/cache), and writes a
JSON array of L2-normalized embedding vectors to stdout -- one per input string, same order.

Runs fully offline once the model is cached (default location: ~/.cache/huggingface/hub/,
outside this repo). Never sends the issue or fix text anywhere except to the local model.

Usage (called by scripts/lib/embeddings.js -- not meant to be run by hand):
    echo '["text one", "text two"]' | python scripts/embed.py

Model is configurable via the EMBEDDING_MODEL env var; defaults to a small, fast,
general-purpose model well suited to short technical text.
"""
import json
import os
import sys

MODEL_NAME = os.environ.get("EMBEDDING_MODEL", "all-MiniLM-L6-v2")


def main():
    raw = sys.stdin.read()
    texts = json.loads(raw)
    if not isinstance(texts, list) or not all(isinstance(t, str) for t in texts):
        raise ValueError("Expected a JSON array of strings on stdin.")

    # Imported here, not at module load, so --help / bad-input errors above don't pay the
    # (slow) torch/transformers import cost.
    from sentence_transformers import SentenceTransformer

    model = SentenceTransformer(MODEL_NAME)
    # normalize_embeddings=True -> unit vectors, so cosine similarity == a plain dot product.
    vectors = model.encode(texts, normalize_embeddings=True, convert_to_numpy=True)

    json.dump({"model": MODEL_NAME, "dimensions": int(vectors.shape[1]), "vectors": vectors.tolist()}, sys.stdout)


if __name__ == "__main__":
    main()
