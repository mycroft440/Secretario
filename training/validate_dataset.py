#!/usr/bin/env python3
import json
from collections import Counter
from pathlib import Path

PATH = Path(__file__).with_name("dataset_seed.jsonl")
TOOLS = {"allowCall", "blockCall", "askForClarification"}
ALLOW_CATEGORIES = {"REAL_PERSON", "DELIVERY", "JOB", "HEALTH", "SERVICE"}
BLOCK_CATEGORIES = {"OPERATOR", "MARKETING", "SCAM", "ROBOT_OR_SILENT"}
MIN_EXAMPLES_PER_TOOL = 10
MAX_TRANSCRIPT_CHARS = 1500

errors = []
counts = Counter()
category_counts = Counter()
seen_transcripts = set()

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
        continue
    if len(transcript) > MAX_TRANSCRIPT_CHARS:
        errors.append(f"linha {line_no}: transcript excede {MAX_TRANSCRIPT_CHARS} caracteres")
    if tool not in TOOLS:
        errors.append(f"linha {line_no}: ferramenta inválida: {tool}")
        continue
    if not isinstance(args, dict):
        errors.append(f"linha {line_no}: arguments deve ser objeto")
        continue

    signature = transcript.strip().casefold()
    if signature in seen_transcripts:
        errors.append(f"linha {line_no}: transcrição duplicada/conflitante")
    seen_transcripts.add(signature)
    counts[tool] += 1

    if tool == "allowCall":
        category = args.get("category")
        category_counts[category] += 1
        if category not in ALLOW_CATEGORIES:
            errors.append(f"linha {line_no}: allowCall não aceita categoria {category}")
        if not isinstance(args.get("callerName"), str):
            errors.append(f"linha {line_no}: callerName deve ser string")
        if not isinstance(args.get("summary"), str) or not args.get("summary", "").strip():
            errors.append(f"linha {line_no}: summary obrigatório")
        confidence = args.get("confidence")
        if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
            errors.append(f"linha {line_no}: confidence deve estar entre 0 e 1")

    elif tool == "blockCall":
        category = args.get("category")
        category_counts[category] += 1
        if category not in BLOCK_CATEGORIES:
            errors.append(f"linha {line_no}: blockCall não aceita categoria {category}")
        if not transcript.strip():
            errors.append(
                f"linha {line_no}: transcrição vazia não pode provar silêncio; use askForClarification"
            )
        if not isinstance(args.get("reason"), str) or not args.get("reason", "").strip():
            errors.append(f"linha {line_no}: reason obrigatório")
        confidence = args.get("confidence")
        if not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
            errors.append(f"linha {line_no}: confidence deve estar entre 0 e 1")

    else:
        if not isinstance(args.get("question"), str) or not args.get("question", "").strip():
            errors.append(f"linha {line_no}: pergunta de esclarecimento ausente")

for tool in sorted(TOOLS):
    if counts[tool] < MIN_EXAMPLES_PER_TOOL:
        errors.append(
            f"dataset desbalanceado: {tool} tem {counts[tool]} exemplos; mínimo {MIN_EXAMPLES_PER_TOOL}"
        )

print(f"arquivo: {PATH}")
print(f"exemplos: {sum(counts.values())}")
for tool in sorted(TOOLS):
    print(f"  {tool}: {counts[tool]}")
print("categorias terminais:")
for category, count in sorted(category_counts.items(), key=lambda item: str(item[0])):
    print(f"  {category}: {count}")

if errors:
    print("\nERROS:")
    for error in errors:
        print(f"- {error}")
    raise SystemExit(1)

print("\nDataset seed válido para desenvolvimento. Ainda não é um conjunto de avaliação de produção.")
