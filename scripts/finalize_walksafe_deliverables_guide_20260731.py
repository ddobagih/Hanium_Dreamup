#!/usr/bin/python3.14
"""Insert real footnotes and a live table of contents into a DOCX via UNO."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import uno
from com.sun.star.beans import PropertyValue


TOC_MARKER = "[[[WS_TOC]]]"


def prop(name: str, value):
    item = PropertyValue()
    item.Name = name
    item.Value = value
    return item


def unique_hit(doc, marker: str):
    query = doc.createSearchDescriptor()
    query.SearchString = marker
    query.SearchCaseSensitive = True
    query.SearchRegularExpression = False
    hits = doc.findAll(query)
    if hits.getCount() != 1:
        raise RuntimeError(
            f"marker must occur exactly once: {marker!r}; got {hits.getCount()}"
        )
    return hits.getByIndex(0)


def connect(pipe_name: str):
    local = uno.getComponentContext()
    resolver = local.ServiceManager.createInstanceWithContext(
        "com.sun.star.bridge.UnoUrlResolver", local
    )
    for _ in range(160):
        try:
            return resolver.resolve(
                f"uno:pipe,name={pipe_name};urp;StarOffice.ComponentContext"
            )
        except Exception:
            time.sleep(0.05)
    raise RuntimeError("LibreOffice UNO connection timeout")


def update_indexes(doc) -> None:
    indexes = doc.getDocumentIndexes()
    for index in range(indexes.getCount()):
        indexes.getByIndex(index).update()


def postprocess(
    input_docx: Path,
    output_docx: Path,
    footnotes_path: Path,
) -> None:
    input_docx = input_docx.resolve(strict=True)
    footnotes_path = footnotes_path.resolve(strict=True)
    output_docx = output_docx.resolve(strict=False)
    output_docx.parent.mkdir(parents=True, exist_ok=True)
    footnotes = json.loads(footnotes_path.read_text(encoding="utf-8"))

    with tempfile.TemporaryDirectory(prefix="walksafe-docx-uno-") as raw:
        profile = Path(raw) / "profile"
        profile.mkdir()
        pipe_name = "walksafe_" + uuid.uuid4().hex
        office = subprocess.Popen(
            [
                "libreoffice",
                "--headless",
                "--nologo",
                "--nodefault",
                "--nofirststartwizard",
                "--norestore",
                f"-env:UserInstallation=file://{profile}",
                f"--accept=pipe,name={pipe_name};urp;StarOffice.ComponentContext",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        doc = None
        try:
            context = connect(pipe_name)
            desktop = context.ServiceManager.createInstanceWithContext(
                "com.sun.star.frame.Desktop", context
            )
            doc = desktop.loadComponentFromURL(
                input_docx.as_uri(),
                "_blank",
                0,
                (prop("Hidden", True), prop("ReadOnly", False)),
            )
            if doc is None:
                raise RuntimeError(f"failed to open: {input_docx}")

            for item in footnotes:
                marker = item["marker"]
                explanation = item["note"]
                hit = unique_hit(doc, marker)
                footnote = doc.createInstance("com.sun.star.text.Footnote")
                hit.getText().insertTextContent(hit, footnote, True)
                note_text = footnote.getText()
                note_text.insertString(
                    note_text.createTextCursor(),
                    explanation,
                    False,
                )

            hit = unique_hit(doc, TOC_MARKER)
            toc = doc.createInstance("com.sun.star.text.ContentIndex")
            toc.Title = "목차"
            toc.Level = 3
            toc.CreateFromOutline = True
            hit.getText().insertTextContent(hit, toc, True)

            update_indexes(doc)

            doc.storeAsURL(
                output_docx.as_uri(),
                (
                    prop("FilterName", "Office Open XML Text"),
                    prop("Overwrite", True),
                ),
            )
            doc.close(True)
            doc = None

            # Reopening lets Writer lay out the document with the materialized
            # index before recalculating page numbers. A second refresh keeps
            # the TOC accurate when its own pagination changes the body pages.
            for _ in range(2):
                doc = desktop.loadComponentFromURL(
                    output_docx.as_uri(),
                    "_blank",
                    0,
                    (prop("Hidden", True), prop("ReadOnly", False)),
                )
                if doc is None:
                    raise RuntimeError(f"failed to reopen: {output_docx}")
                update_indexes(doc)
                doc.store()
                doc.close(True)
                doc = None
        finally:
            if doc is not None:
                try:
                    doc.close(True)
                except Exception:
                    pass
            office.terminate()
            try:
                office.wait(timeout=5)
            except subprocess.TimeoutExpired:
                office.kill()
                office.wait(timeout=5)


def main() -> int:
    if len(sys.argv) != 4:
        print(
            "usage: finalize_walksafe_deliverables_guide_20260731.py "
            "<input.docx> <output.docx> <footnotes.json>",
            file=sys.stderr,
        )
        return 2
    postprocess(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
