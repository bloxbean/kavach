# Maintaining the white paper

Edit `README.md` for the canonical text and Mermaid concept diagrams. `build.py` contains
presentation styles and equivalent vector illustrations for HTML/PDF. When a concept diagram
changes, update its vector counterpart as part of the same review; the vector drawings are
not automatically laid out from Mermaid.

Build from the repository root with Python 3, ReportLab 4.4.9 and Pandoc on PATH:

```sh
python3 docs/whitepaper/build.py
```

Outputs:

- `docs/whitepaper/kavach-whitepaper.html`: self-contained reading edition with local vector
  graphics, chapter navigation, reading progress and print styles. No CDN or analytics.
- `docs/whitepaper/figures/`: reusable SVG illustrations.
- `output/pdf/kavach-whitepaper-v0.1.pdf`: PDF with embedded fonts, vector graphics and bookmarks.

On macOS the PDF embeds local Arial/Georgia fonts. Other systems use PDF standard fonts;
recheck wrapping and pagination there. Source-reference links are pinned to the reviewed
implementation baseline. Update the baseline/version/date in the document and builder
when publishing a revised edition; do not silently relabel old measurements.

Check every local Markdown link and HTML chapter anchor. Render every PDF page and review
heading placement, tables, diagrams and footers before release. Confirm that prose/diagrams
match their source specifications and that future work remains clearly labeled. The HTML
is the primary accessible reading edition; the generated PDF is not a tagged PDF/UA document.

The September 2026 edition was checked with the bundled Python/ReportLab runtime, a native
CoreGraphics page renderer, and the in-app browser. No contracts, wallet keys or deployment
configuration are changed by the document build.
