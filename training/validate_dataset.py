#!/usr/bin/env python3
import json
from collections import Counter
from pathlib import Path

PATH = Path(__file__).with_name("dataset_seed.jsonl")
TOOLS = {"allowCall", "blockCall", "askForClarification"}
CATEGORIES = {
    "REAL_PERSON", "OPERATOR", "MARKETING", "ROBOT_OR_SILENT",
    "DELIVERY", "JOB", "HEALTH", "SERVICE", "UNKNOWN"
}

errors = []
counts = Counter()
seen = set()

for line_no, raw in enumerate(PATH.read_text(encoding="utf-8").splitlines(), 1):
    if not raw.strip():
        continue
    try:
        row = json.loads(raw)
    except json.JSONDecodeError as exc:
        errors.append(f"linha {line_no}: JSON inválido: {exc}")
        continue

    transcript = row.get("transcript")
    tool = row.get("expected_tool")
    args = row.get("arguments")
    if not isinstance(transcript, str):
        errors.append(f"linha {line_no}: transcript deve ser string")
    if tool not in TOOLS:
        errors.append(f"linha {line_no}: ferramenta inválida: {tool}")
        continue
    if not isinstance(args, dict):
        errors.append(f"linha {line_no}: arguments deve ser objeto")
        continue

    signature = (transcript.strip().lower(), tool)
    if signature in seen:
        errors.append(f"linha {line_no}: exemplo duplicado")
    seen.add(signature)
    counts[tool] += 1

    if tool in {"allowCall", "blockCall"}:
        category = args.get("category")
        if category not in CATEGORIES:
            errors.append(f"linha {line_no}: categoria inválida: {category}")
        confidence = args.get("confidence")
        if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
            errors.append(f"linha {line_no}: confidence deve estar entre 0 e 1")
    if tool == "askForClarification" and not args.get("question"):
        errors.append(f"linha {line_no}: pergunta de esclarecimento ausente")

print(f"arquivo: {PATH}")
print(f"exemplos: {sum(counts.values())}")
for tool in sorted(TOOLS):
    print(f"  {tool}: {counts[tool]}")

if errors:
    print("\nERROS:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("\nDataset seed válido.")
