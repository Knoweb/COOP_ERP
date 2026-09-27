# Report templates (K-06b, 19A section 6)

A template is `{templateId}.html` in this folder, a Thymeleaf HTML page that `A4Renderer.render`
fills and Chromium prints to an A4 PDF on the worker role. A module adds its own template here
(M3's shelf label). Rules:

- Values are written with `th:text` (escaped), never `th:utext`.
- The kernel puts the charset, a content security policy, the A4 page size and the three Noto
  families (Noto Sans, Noto Sans Sinhala, Noto Sans Tamil, SIL OFL, `../fonts/OFL.txt`) into
  `<head>`; the page must have a `<head>`, and may load nothing from outside itself (images as
  `data:` URIs only).
- `${data}` is the map the module passed, `${lang}` the language code, `#{id}` a message of the
  kernel's catalogue (`i18n/kernel/*.json`) in that language.
- Amounts, quantities and dates arrive formatted (`Formats`): a template does no arithmetic.

## document-a4

The generic trading document, for M4's invoice and notes until they have templates of their own.
Every key is optional except `title`.

| Key | Shape |
|---|---|
| `title` | text, "Invoice" in the reader's language |
| `number`, `date` | text |
| `references` | list of `{label, value}` (order number, delivery note ...) |
| `issuer`, `from`, `to` | `{title, lines}`, `lines` one text with line breaks |
| `lines` | list of `{description, quantity, unitPrice, amount}` |
| `totals` | list of `{label, value}`; the last one is printed as the grand total |
| `notes`, `footer` | text with line breaks |
