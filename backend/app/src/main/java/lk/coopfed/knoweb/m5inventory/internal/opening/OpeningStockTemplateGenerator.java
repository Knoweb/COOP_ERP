package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates an official, standard OpenXML (.xlsx) spreadsheet template for opening stock imports (PGL-M2-06).
 */
public final class OpeningStockTemplateGenerator {

    private OpeningStockTemplateGenerator() {}

    public static byte[] generateWorkbook() {
        return generateTemplateBytes();
    }

    public static byte[] generateTemplateBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {

            // 1. [Content_Types].xml
            putEntry(
                    zos,
                    "[Content_Types].xml",
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                      <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>
                    """);

            // 2. _rels/.rels
            putEntry(
                    zos,
                    "_rels/.rels",
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);

            // 3. xl/_rels/workbook.xml.rels
            putEntry(
                    zos,
                    "xl/_rels/workbook.xml.rels",
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>
                    """);

            // 4. xl/workbook.xml
            putEntry(
                    zos,
                    "xl/workbook.xml",
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets>
                        <sheet name="OpeningStock" sheetId="1" r:id="rId1"/>
                      </sheets>
                    </workbook>
                    """);

            // 5. xl/styles.xml
            putEntry(
                    zos,
                    "xl/styles.xml",
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <fonts count="1"><font><name val="Calibri"/><sz val="11"/></font></fonts>
                      <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                      <borders count="1"><border/></borders>
                      <cellStyleXfs count="1"><xf/></cellStyleXfs>
                      <cellXfs count="1"><xf/></cellXfs>
                    </styleSheet>
                    """);

            // 6. xl/worksheets/sheet1.xml
            putEntry(
                    zos,
                    "xl/worksheets/sheet1.xml",
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1">
                          <c r="A1" t="inlineStr"><is><t>sku_code_or_barcode</t></is></c>
                          <c r="B1" t="inlineStr"><is><t>item_name</t></is></c>
                          <c r="C1" t="inlineStr"><is><t>qty</t></is></c>
                          <c r="D1" t="inlineStr"><is><t>uom</t></is></c>
                          <c r="E1" t="inlineStr"><is><t>unit_cost</t></is></c>
                          <c r="F1" t="inlineStr"><is><t>batch_no</t></is></c>
                          <c r="G1" t="inlineStr"><is><t>expiry_date</t></is></c>
                          <c r="H1" t="inlineStr"><is><t>printed_mrp</t></is></c>
                          <c r="I1" t="inlineStr"><is><t>condition</t></is></c>
                        </row>
                        <row r="2">
                          <c r="A2" t="inlineStr"><is><t>DHAL1</t></is></c>
                          <c r="B2" t="inlineStr"><is><t>Red Split Lentils 1kg</t></is></c>
                          <c r="C2"><v>150.000</v></c>
                          <c r="D2" t="inlineStr"><is><t>KG</t></is></c>
                          <c r="E2"><v>310.5000</v></c>
                          <c r="F2" t="inlineStr"><is><t>D1</t></is></c>
                          <c r="G2" t="inlineStr"><is><t>2027-05-31</t></is></c>
                          <c r="H2"><v>360.00</v></c>
                          <c r="I2" t="inlineStr"><is><t>GOOD</t></is></c>
                        </row>
                        <row r="3">
                          <c r="A3" t="inlineStr"><is><t>RICE-SAMBA-5KG</t></is></c>
                          <c r="B3" t="inlineStr"><is><t>Samba Rice 5kg</t></is></c>
                          <c r="C3"><v>40.000</v></c>
                          <c r="D3" t="inlineStr"><is><t>NOS</t></is></c>
                          <c r="E3"><v>1150.0000</v></c>
                          <c r="F3" t="inlineStr"><is><t></t></is></c>
                          <c r="G3" t="inlineStr"><is><t></t></is></c>
                          <c r="H3" t="inlineStr"><is><t></t></is></c>
                          <c r="I3" t="inlineStr"><is><t>GOOD</t></is></c>
                        </row>
                        <row r="4">
                          <c r="A4" t="inlineStr"><is><t>MILK-POWDER-400G</t></is></c>
                          <c r="B4" t="inlineStr"><is><t>Anchor Milk Powder 400g</t></is></c>
                          <c r="C4"><v>25.000</v></c>
                          <c r="D4" t="inlineStr"><is><t>PKT</t></is></c>
                          <c r="E4"><v>980.0000</v></c>
                          <c r="F4" t="inlineStr"><is><t>B-2026-X</t></is></c>
                          <c r="G4" t="inlineStr"><is><t>2026-12-31</t></is></c>
                          <c r="H4"><v>1050.00</v></c>
                          <c r="I4" t="inlineStr"><is><t>GOOD</t></is></c>
                        </row>
                        <row r="5">
                          <c r="A5" t="inlineStr"><is><t>DHAL1</t></is></c>
                          <c r="B5" t="inlineStr"><is><t>Red Split Lentils 1kg</t></is></c>
                          <c r="C5"><v>2.000</v></c>
                          <c r="D5" t="inlineStr"><is><t>KG</t></is></c>
                          <c r="E5"><v>310.5000</v></c>
                          <c r="F5" t="inlineStr"><is><t>D1</t></is></c>
                          <c r="G5" t="inlineStr"><is><t>2027-05-31</t></is></c>
                          <c r="H5"><v>360.00</v></c>
                          <c r="I5" t="inlineStr"><is><t>DAMAGED</t></is></c>
                        </row>
                      </sheetData>
                    </worksheet>
                    """);

            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate template workbook", e);
        }
    }

    private static void putEntry(ZipOutputStream zos, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zos.putNextEntry(entry);
        zos.write(content.trim().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
