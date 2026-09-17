#!/usr/bin/env bash
# Regenerates the read-only text extracts that people and AI tools read instead of
# the PDF and Word files. See docs/README.md, section "Text extracts".
#
# Usage (from anywhere, bash on Windows/Git Bash, Linux or macOS):
#   docs/tools/extract-text.sh                                  # every document
#   docs/tools/extract-text.sh docs/design/18_Core_Data_Model.pdf   # only these files
#
# Output: <folder>/txt/<same base name>.txt, UTF-8, LF line endings.
#   .pdf  -> pdftotext -layout -enc UTF-8 -eol unix   (Xpdf 4.x or Poppler)
#   .docx -> docx2txt                                  (writes <name>.txt beside the input;
#                                                       we move it into txt/)
# A document that already has a Markdown source in docs/sources/ is skipped: the
# source is what tools read from then on, and its extract is deleted (docs/README.md).
set -euo pipefail

cd "$(dirname "$0")/../.."   # repository root

need() { command -v "$1" >/dev/null 2>&1 || { echo "error: '$1' is not installed or not on PATH" >&2; exit 1; }; }

extract_one() {
  local src="$1"
  local dir base stem out
  dir=$(dirname "$src"); base=$(basename "$src"); stem="${base%.*}"
  out="$dir/txt/$stem.txt"

  if [ -f "docs/sources/$stem.md" ]; then
    echo "skip  $src (has a source: docs/sources/$stem.md)"
    return
  fi
  mkdir -p "$dir/txt"

  case "$src" in
    *.pdf)
      need pdftotext
      pdftotext -layout -enc UTF-8 -eol unix "$src" "$out"
      ;;
    *.docx)
      need docx2txt
      local tmp
      tmp=$(mktemp -d)
      cp "$src" "$tmp/$base"
      docx2txt "$tmp/$base" >/dev/null
      # docx2txt writes <stem>.txt next to its input; normalise line endings on the way.
      sed 's/\r$//' "$tmp/$stem.txt" > "$out"
      rm -rf "$tmp"
      ;;
    *)
      echo "skip  $src (not a .pdf or .docx)"
      return
      ;;
  esac
  echo "wrote $out"
}

if [ "$#" -eq 0 ]; then
  set -- docs/requirements/*.pdf docs/requirements/*.docx docs/design/*.pdf docs/design/*.docx
fi

for f in "$@"; do
  [ -f "$f" ] && extract_one "$f"
done
