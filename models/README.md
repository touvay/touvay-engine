# Test models (not checked in)

Model weights are never committed; fetch with `scripts/fetch-model.ps1` (hash-verified).

## qwen2.5-0.5b-instruct-q4_k_m.gguf

| Field | Value |
|---|---|
| Model | Qwen2.5-0.5B-Instruct, Q4_K_M GGUF quantization |
| Source | [Qwen/Qwen2.5-0.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) (official Qwen repository) |
| License | **Apache-2.0** — redistributable, commercial use permitted |
| Size | 491,400,032 bytes (~468 MiB) |
| SHA-256 | `74A4DA8C9FDBCD15BD1F6D01D621410D31C6FC00986F5EB687824E7B93D7A9DB` |
| Used by | Production llama.cpp TCK (`runtime-llamacpp`) and `apps/benchmark` |

Why this model: smallest instruct-tuned model in a family with credible
rewrite/summarize behavior, official GGUF from the model authors, permissive license that
allows redistribution in a future model pack, and a size (~0.5 GB) representative of what
a T1 (4 GB) device could realistically host per ARCHITECTURE.md §14.3.

Push to the benchmark app before running:

```
adb push models/qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Android/data/com.touvay.benchmark/files/model.gguf
```
