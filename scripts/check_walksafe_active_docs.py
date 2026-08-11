#!/usr/bin/env python3
"""Check current navigation documents without traversing historical evidence."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import shlex
import unicodedata
from urllib.parse import unquote, urlsplit


RUNNER = Path("scripts/run_walksafe_test_layers_current.sh")
HISTORICAL_RUNNER = Path("scripts/run_walksafe_test_layers_20260711.sh")
FIXED_ACTIVE_DOCS = (
    Path("README.md"),
    Path("AGENTS.md"),
    Path("CONTRIBUTING.md"),
    Path("SECURITY.md"),
    Path("apps/README.md"),
    Path("backend/README.md"),
    Path("backend/app/README.md"),
    Path("backend/app/api/README.md"),
    Path("backend/app/services/README.md"),
    Path("configs/README.md"),
    Path("contracts/README.md"),
    Path("data_sources/README.md"),
    Path("data_sources/scripts/README.md"),
    Path("deploy/README.md"),
    Path("docs/README.md"),
    Path("docs/catalogs/README.md"),
    Path("docs/control/README.md"),
    Path("docs/control/goals/README.md"),
    Path("docs/testing/README.md"),
    Path("docs/testing/test_layers_20260711.md"),
    Path("legacy/README.md"),
    Path("model/README.md"),
    Path("scripts/README.md"),
    Path("templates/README.md"),
    Path("tests/README.md"),
)
REFERENCE_DEFINITION_RE = re.compile(
    r"^\s{0,3}\[([^\]]+)\]:\s*(<[^>\n]+>|\S+)", re.MULTILINE
)
REFERENCE_USAGE_RE = re.compile(r"!?\[([^\]]+)\]\[([^\]]*)\]")
SHORTCUT_REFERENCE_RE = re.compile(r"!?\[([^\]\n]+)\]")
INLINE_CODE_RE = re.compile(r"`([^`\n]+)`")
SCRIPT_REFERENCE_RE = re.compile(
    r"(?<![A-Za-z0-9_./:-])"
    r"(?P<path>(?:(?:\.\./)+|\./)?(?:data_sources/scripts|scripts)/"
    r"[A-Za-z0-9_./-]+\.(?:py|sh))"
)
RUNNER_FILENAME_RE = re.compile(
    r"(?P<name>run_walksafe_test_layers_(?:current|20260711)\.sh)"
)
RUNNER_FAMILY_RE = re.compile(r"run_walksafe_test_layers_")
TABLE_SELECTOR_RE = re.compile(r"^\|\s*`([a-z][a-z0-9_-]*)`\s*\|", re.MULTILINE)
CASE_SELECTOR_RE = re.compile(r"^\s{2}([a-z][a-z0-9_-]*)\)\s*", re.MULTILINE)
USAGE_SELECTORS_RE = re.compile(r"Usage:.*\{([a-z0-9_|-]+)\}")


@dataclass(frozen=True)
class CheckResult:
    errors: tuple[str, ...]
    document_count: int
    local_link_count: int
    script_reference_count: int
    runner_selectors: tuple[str, ...]


def active_documents(root: Path) -> tuple[Path, ...]:
    guides = tuple(
        path.relative_to(root)
        for path in sorted((root / "docs" / "guides").rglob("*.md"))
    )
    return (*FIXED_ACTIVE_DOCS, *guides)


def _without_html_comments(text: str) -> str:
    return re.sub(
        r"<!--.*?-->",
        lambda match: "\n" * match.group(0).count("\n"),
        text,
        flags=re.DOTALL,
    )


def _without_fenced_code(text: str) -> str:
    output: list[str] = []
    fence_character: str | None = None
    fence_length = 0
    for line in text.splitlines(keepends=True):
        opening = re.match(r"^\s{0,3}(`{3,}|~{3,})(.*)$", line)
        closing = (
            re.match(r"^\s{0,3}(`{3,}|~{3,})\s*$", line)
            if fence_character is not None
            else None
        )
        if fence_character is None and opening:
            marker = opening.group(1)
            if marker[0] == "~" or "`" not in opening.group(2):
                fence_character = marker[0]
                fence_length = len(marker)
            output.append("\n" if line.endswith("\n") else "")
        elif (
            fence_character is not None
            and closing is not None
            and closing.group(1)[0] == fence_character
            and len(closing.group(1)) >= fence_length
        ):
            fence_character = None
            fence_length = 0
            output.append("\n" if line.endswith("\n") else "")
        elif fence_character is None:
            output.append(line)
        else:
            output.append("\n" if line.endswith("\n") else "")
    return _without_html_comments("".join(output))


def _markdown_anchors(path: Path) -> set[str]:
    text = _without_fenced_code(path.read_text(encoding="utf-8"))
    anchors = set(
        re.findall(r"<(?:a|[A-Za-z][A-Za-z0-9:-]*)\s+[^>]*id=[\"']([^\"']+)", text)
    )
    occurrences: dict[str, int] = {}
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$", line)
        if match:
            heading_source = match.group(1)
        elif (
            line.strip()
            and index + 1 < len(lines)
            and re.match(r"^\s{0,3}(?:=+|-+)\s*$", lines[index + 1])
        ):
            paragraph_start = index
            while paragraph_start and lines[paragraph_start - 1].strip():
                paragraph_start -= 1
            heading_source = " ".join(
                part.strip() for part in lines[paragraph_start : index + 1]
            )
        else:
            continue
        heading = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", heading_source)
        heading = re.sub(r"<[^>]+>", "", heading)
        heading = heading.replace("`", "").replace("*", "")
        slug = "".join(
            character
            for character in heading.lower()
            if character in "-_ "
            or character.isspace()
            or unicodedata.category(character)[0] in {"L", "N"}
        )
        slug = re.sub(r"\s+", "-", slug.strip())
        if not slug:
            continue
        duplicate = occurrences.get(slug, 0)
        occurrences[slug] = duplicate + 1
        anchors.add(slug if duplicate == 0 else f"{slug}-{duplicate}")
    return anchors


def _link_target(raw: str) -> str:
    value = raw.strip()
    if value.startswith("<") and ">" in value:
        return value[1 : value.index(">")]
    return value.split(maxsplit=1)[0]


def _inline_link_records(text: str) -> tuple[tuple[str, int, int], ...]:
    records: list[tuple[str, int, int]] = []
    cursor = 0
    while True:
        delimiter = text.find("](", cursor)
        if delimiter < 0:
            break
        label_start = text.rfind("[", 0, delimiter)
        if label_start < 0 or "\n" in text[label_start:delimiter]:
            cursor = delimiter + 2
            continue
        target_start = delimiter + 2
        while target_start < len(text) and text[target_start].isspace():
            target_start += 1
        if target_start >= len(text):
            break

        target_end: int | None = None
        outer_end: int | None = None
        depth = 0
        quote: str | None = None
        index = target_start
        if text[index] == "<":
            angle_end = text.find(">", index + 1)
            if angle_end < 0:
                cursor = delimiter + 2
                continue
            target_end = angle_end + 1
            index = target_end
        while index < len(text):
            character = text[index]
            if character == "\\":
                index += 2
                continue
            if target_end is not None and character in {'"', "'"}:
                quote = None if quote == character else character if quote is None else quote
                index += 1
                continue
            if quote is not None:
                index += 1
                continue
            if character == "(":
                depth += 1
            elif character == ")":
                if depth:
                    depth -= 1
                else:
                    if target_end is None:
                        target_end = index
                    outer_end = index
                    break
            elif character.isspace() and depth == 0 and target_end is None:
                target_end = index
            index += 1
        if target_end is None or outer_end is None or target_end <= target_start:
            cursor = delimiter + 2
            continue
        span_start = label_start - 1 if label_start and text[label_start - 1] == "!" else label_start
        records.append((text[target_start:target_end], span_start, outer_end + 1))
        cursor = outer_end + 1
    return tuple(records)


def _blank_spans(text: str, spans: tuple[tuple[int, int], ...]) -> str:
    characters = list(text)
    for start, end in spans:
        for index in range(start, end):
            if characters[index] != "\n":
                characters[index] = " "
    return "".join(characters)


def _reference_id(value: str) -> str:
    return " ".join(value.split()).casefold()


def _markdown_link_targets(text: str) -> tuple[tuple[str, ...], tuple[str, ...]]:
    visible = _without_fenced_code(text)
    code_matches = tuple(INLINE_CODE_RE.finditer(visible))
    visible = _blank_spans(
        visible,
        tuple((match.start(), match.end()) for match in code_matches),
    )
    inline = _inline_link_records(visible)
    targets = [record[0] for record in inline]
    definitions: dict[str, str] = {}
    errors: list[str] = []
    definition_matches = tuple(REFERENCE_DEFINITION_RE.finditer(visible))
    for match in definition_matches:
        identifier = _reference_id(match.group(1))
        if identifier in definitions:
            errors.append(f"duplicate reference definition: {identifier}")
        else:
            definitions[identifier] = match.group(2)
    masked = _blank_spans(visible, tuple((start, end) for _, start, end in inline))
    masked = _blank_spans(
        masked,
        tuple((match.start(), match.end()) for match in definition_matches),
    )
    usage_matches = tuple(REFERENCE_USAGE_RE.finditer(masked))
    for match in usage_matches:
        identifier = _reference_id(match.group(2) or match.group(1))
        target = definitions.get(identifier)
        if target is None:
            errors.append(f"undefined reference link: {identifier}")
        else:
            targets.append(target)
    masked = _blank_spans(
        masked,
        tuple((match.start(), match.end()) for match in usage_matches),
    )
    for match in SHORTCUT_REFERENCE_RE.finditer(masked):
        raw_identifier = match.group(1).strip()
        if not raw_identifier:
            continue
        identifier = _reference_id(raw_identifier)
        target = definitions.get(identifier)
        line_start = masked.rfind("\n", 0, match.start()) + 1
        is_task_box = bool(
            re.fullmatch(r"\s*[-+*]\s*", masked[line_start : match.start()])
            and raw_identifier.casefold() == "x"
        )
        if is_task_box:
            continue
        if target is not None:
            targets.append(target)
        elif raw_identifier[0].isalpha() and all(
            character.isalnum() or character in " _-" for character in raw_identifier
        ):
            errors.append(f"undefined reference link: {identifier}")
    return tuple(targets), tuple(errors)


def _check_links(root: Path, document: Path, text: str) -> tuple[list[str], int]:
    raw_targets, reference_errors = _markdown_link_targets(text)
    errors = [f"{document}: {error}" for error in reference_errors]
    count = 0
    source = root / document
    for raw_target in raw_targets:
        target = re.sub(r"\\([!\"#$%&'()*+,\-./:;<=>?@\[\]^_`{|}~\\])", r"\1", _link_target(raw_target))
        try:
            parsed = urlsplit(target)
        except ValueError as exc:
            errors.append(f"{document}: {target}: malformed link ({exc})")
            continue
        if parsed.scheme or parsed.netloc:
            continue
        count += 1
        relative_target = unquote(parsed.path)
        if relative_target.startswith("/"):
            errors.append(f"{document}: {target}: local link must be relative")
            continue
        destination = source if not relative_target else source.parent / relative_target
        try:
            destination.resolve().relative_to(root.resolve())
        except ValueError:
            errors.append(f"{document}: {target}: local link escapes repository")
            continue
        if not destination.exists():
            errors.append(f"{document}: {target}: path does not exist")
            continue
        if parsed.fragment and destination.is_file() and destination.suffix.lower() == ".md":
            fragment = unquote(parsed.fragment)
            if fragment not in _markdown_anchors(destination):
                errors.append(f"{document}: {target}: fragment does not exist")
    return errors, count


def _check_script_references(root: Path, document: Path, text: str) -> tuple[list[str], int]:
    errors: list[str] = []
    references: list[str] = []
    for match in SCRIPT_REFERENCE_RE.finditer(text):
        reference = match.group("path")
        references.append(reference)
        repository_text = re.sub(r"^(?:(?:\.\./)+|\./)", "", reference)
        repository_path = Path(repository_text)
        if ".." in repository_path.parts:
            errors.append(f"{document}: {repository_path}: script reference escapes script root")
            continue
        destination = (root / repository_path).resolve()
        try:
            destination.relative_to(root.resolve())
        except ValueError:
            errors.append(f"{document}: {repository_path}: script reference escapes repository")
            continue
        if not destination.is_file():
            errors.append(f"{document}: {repository_path}: script does not exist")
    return errors, len(references)


def _runner_fragments(text: str) -> tuple[tuple[str, str], ...]:
    fragments: list[tuple[str, str]] = []
    fence_character: str | None = None
    fence_length = 0
    for line in _without_html_comments(text).splitlines():
        if fence_character is None:
            opening = re.match(r"^\s{0,3}(`{3,}|~{3,})(.*)$", line)
            if opening and (opening.group(1)[0] == "~" or "`" not in opening.group(2)):
                fence_character = opening.group(1)[0]
                fence_length = len(opening.group(1))
                continue
            code_matches = tuple(INLINE_CODE_RE.finditer(line))
            fragments.extend(("inline", match.group(1)) for match in code_matches)
            remaining = _blank_spans(
                line,
                tuple((match.start(), match.end()) for match in code_matches),
            )
            links = _inline_link_records(remaining)
            for _, start, end in links:
                link_source = remaining[start:end]
                label_end = link_source.find("](")
                if label_end > 0:
                    label_start = 2 if link_source.startswith("![") else 1
                    fragments.append(("inline", link_source[label_start:label_end]))
            remaining = _blank_spans(
                remaining,
                tuple((start, end) for _, start, end in links),
            )
            if not REFERENCE_DEFINITION_RE.match(remaining):
                fragments.append(("line", remaining))
            continue

        closing = re.match(r"^\s{0,3}(`{3,}|~{3,})\s*$", line)
        if (
            closing
            and closing.group(1)[0] == fence_character
            and len(closing.group(1)) >= fence_length
        ):
            fence_character = None
            fence_length = 0
        else:
            fragments.append(("shell", line))
    return tuple(fragments)


def _inline_runner_reference(fragment: str, name: str) -> bool:
    return bool(
        re.fullmatch(
            rf"(?:(?:(?:\./|\.\./)*)scripts/)?{re.escape(name)}",
            fragment.strip(),
        )
    )


def _shell_tokens(fragment: str) -> tuple[str, ...]:
    try:
        lexer = shlex.shlex(fragment, posix=True, punctuation_chars="|;&()<>")
        lexer.whitespace_split = True
        lexer.commenters = ""
        return tuple(lexer)
    except ValueError:
        return (fragment,)


def _has_dynamic_runner_family(fragment: str) -> bool:
    exact_starts = {match.start() for match in RUNNER_FILENAME_RE.finditer(fragment)}
    return any(
        match.start() not in exact_starts
        for match in RUNNER_FAMILY_RE.finditer(fragment)
    )


def _canonical_runner_token(token: str, name: str) -> bool:
    return bool(re.fullmatch(rf"(?:\./)*scripts/{re.escape(name)}", token))


def _has_attached_fd_redirect(fragment: str, name: str, descriptor: str) -> bool:
    for match in RUNNER_FILENAME_RE.finditer(fragment):
        if match.group("name") != name:
            continue
        tail = fragment[match.end() :]
        if re.match(
            rf"[`\"')\]}}]*\s+{re.escape(descriptor)}[<>]",
            tail,
        ):
            return True
    return False


def _runner_commands(document: Path, text: str) -> tuple[list[str], set[str]]:
    errors: list[str] = []
    documented: set[str] = set()
    for fragment_kind, fragment in _runner_fragments(text):
        if _has_dynamic_runner_family(fragment):
            errors.append(
                f"{document}: dynamic or historical runner command is forbidden; use {RUNNER}"
            )
            continue
        tokens = _shell_tokens(fragment)
        for token_index, token in enumerate(tokens):
            match = RUNNER_FILENAME_RE.search(token)
            if match is None:
                continue
            name = match.group("name")
            if fragment_kind == "inline" and _inline_runner_reference(fragment, name):
                continue
            if name == HISTORICAL_RUNNER.name:
                errors.append(
                    f"{document}: historical runner command is forbidden; use {RUNNER}"
                )
                continue
            suffix = token[match.end() :].strip("`)]}")
            if suffix:
                errors.append(
                    f"{document}: malformed runner path suffix {suffix!r} after {name}"
                )
                continue
            if not _canonical_runner_token(token, name):
                errors.append(
                    f"{document}: runner command must use canonical repository path scripts/{name}"
                )
                continue
            selector = "all"
            if token_index + 1 < len(tokens):
                next_token = tokens[token_index + 1]
                shell_boundary = bool(re.fullmatch(r"[|;&()<>]+", next_token))
                file_descriptor_redirect = bool(
                    next_token.isdecimal()
                    and token_index + 2 < len(tokens)
                    and re.fullmatch(r"[<>][|&<>]*", tokens[token_index + 2])
                    and _has_attached_fd_redirect(fragment, name, next_token)
                )
                if not shell_boundary and not file_descriptor_redirect:
                    selector = next_token
            documented.add(selector)
    return errors, documented


def _runner_contract(root: Path, documented: set[str]) -> tuple[list[str], tuple[str, ...]]:
    errors: list[str] = []
    runner_path = root / RUNNER
    if not runner_path.is_file():
        return [f"{RUNNER}: runner does not exist"], ()
    text = runner_path.read_text(encoding="utf-8")
    case_selectors = set(CASE_SELECTOR_RE.findall(text))
    usage_match = USAGE_SELECTORS_RE.search(text)
    usage_selectors = set(usage_match.group(1).split("|")) if usage_match else set()
    if case_selectors != usage_selectors:
        errors.append(
            "runner case/usage selectors differ: "
            f"case={sorted(case_selectors)} usage={sorted(usage_selectors)}"
        )
    for selector in sorted(documented - case_selectors):
        errors.append(f"{selector}: documented selector is missing from runner case")
    for selector in sorted(documented - usage_selectors):
        errors.append(f"{selector}: documented selector is missing from runner usage")
    for selector in sorted(case_selectors - documented):
        errors.append(f"{selector}: runner selector is not documented in active guides")
    return errors, tuple(sorted(case_selectors))


def check_active_docs(root: Path, documents: tuple[Path, ...] | None = None) -> CheckResult:
    root = root.resolve()
    selected = documents if documents is not None else active_documents(root)
    errors: list[str] = []
    local_link_count = 0
    script_reference_count = 0
    documented_selectors: set[str] = set()
    for document in selected:
        path = root / document
        if not path.is_file():
            errors.append(f"{document}: active document does not exist")
            continue
        text = path.read_text(encoding="utf-8")
        link_errors, link_count = _check_links(root, document, text)
        script_errors, script_count = _check_script_references(root, document, text)
        errors.extend(link_errors)
        errors.extend(script_errors)
        local_link_count += link_count
        script_reference_count += script_count
        runner_errors, runner_commands = _runner_commands(document, text)
        errors.extend(runner_errors)
        documented_selectors.update(runner_commands)
        documented_selectors.update(TABLE_SELECTOR_RE.findall(text))
    runner_errors, runner_selectors = _runner_contract(root, documented_selectors)
    errors.extend(runner_errors)
    return CheckResult(
        errors=tuple(sorted(set(errors))),
        document_count=len(selected),
        local_link_count=local_link_count,
        script_reference_count=script_reference_count,
        runner_selectors=runner_selectors,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="run the read-only check")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    if not args.check:
        parser.error("--check is required")
    return args


def main() -> int:
    args = parse_args()
    result = check_active_docs(args.root)
    if result.errors:
        for error in result.errors:
            print(f"ERROR: {error}")
        return 1
    print(
        "PASS: "
        f"active_docs={result.document_count} "
        f"local_links={result.local_link_count} "
        f"script_refs={result.script_reference_count} "
        f"runner_selectors={len(result.runner_selectors)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
