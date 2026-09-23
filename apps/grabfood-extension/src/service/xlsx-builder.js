(() => {
  "use strict";

  const encoder = new TextEncoder();

  function buildOrdersWorkbookBase64(orders, dateKey) {
    const sorted = [...(orders || [])].sort((a, b) => new Date(a.receivedAt || a.recordedAt) - new Date(b.receivedAt || b.recordedAt));
    const rows = [
      ["STT", "Mã đơn hàng", "SĐT", "Thời gian nhận đơn"],
      ...sorted.map((order, index) => [
        index + 1,
        String(order.orderId || "").toUpperCase(),
        normalizePhone(order.phone),
        formatVietnamDateTime(order.receivedAt || order.recordedAt)
      ])
    ];

    const files = [
      { name: "[Content_Types].xml", data: contentTypesXml() },
      { name: "_rels/.rels", data: rootRelsXml() },
      { name: "xl/workbook.xml", data: workbookXml() },
      { name: "xl/_rels/workbook.xml.rels", data: workbookRelsXml() },
      { name: "xl/styles.xml", data: stylesXml() },
      { name: "xl/worksheets/sheet1.xml", data: worksheetXml(rows) },
      { name: "docProps/core.xml", data: corePropsXml(dateKey) },
      { name: "docProps/app.xml", data: appPropsXml() }
    ];

    return bytesToBase64(createZip(files));
  }

  function normalizePhone(value) {
    let digits = String(value || "").replace(/\D/g, "");
    if (digits.startsWith("84") && digits.length >= 11) digits = `0${digits.slice(2)}`;
    if (!digits.startsWith("0") && digits.length === 9) digits = `0${digits}`;
    return digits;
  }

  function worksheetXml(rows) {
    const rowXml = rows.map((row, rowIndex) => {
      const rowNumber = rowIndex + 1;
      const cells = row.map((value, colIndex) => {
        const ref = `${columnName(colIndex + 1)}${rowNumber}`;
        const isHeader = rowIndex === 0;
        const isNumber = rowIndex > 0 && colIndex === 0;
        const isPhone = rowIndex > 0 && colIndex === 2;

        if (isNumber) {
          return `<c r="${ref}"><v>${Number(value) || 0}</v></c>`;
        }

        const style = isHeader ? ' s="1"' : isPhone ? ' s="2"' : "";
        return `<c r="${ref}" t="inlineStr"${style}><is><t xml:space="preserve">${escapeXml(value)}</t></is></c>`;
      }).join("");
      return `<row r="${rowNumber}">${cells}</row>`;
    }).join("");

    const lastRow = Math.max(1, rows.length);
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
  <sheetFormatPr defaultRowHeight="15"/>
  <cols>
    <col min="1" max="1" width="7" customWidth="1"/>
    <col min="2" max="2" width="18" customWidth="1"/>
    <col min="3" max="3" width="18" customWidth="1"/>
    <col min="4" max="4" width="26" customWidth="1"/>
  </cols>
  <sheetData>${rowXml}</sheetData>
  <autoFilter ref="A1:D${lastRow}"/>
</worksheet>`;
  }

  function stylesXml() {
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <numFmts count="1"><numFmt numFmtId="164" formatCode="@"/></numFmts>
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font>
    <font><b/><sz val="11"/><name val="Calibri"/><family val="2"/><scheme val="minor"/></font>
  </fonts>
  <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="3">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
    <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
  </cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>`;
  }

  function contentTypesXml() {
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>`;
  }

  function rootRelsXml() {
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>`;
  }

  function workbookXml() {
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <bookViews><workbookView xWindow="0" yWindow="0" windowWidth="24000" windowHeight="12000"/></bookViews>
  <sheets><sheet name="Đơn hàng" sheetId="1" r:id="rId1"/></sheets>
</workbook>`;
  }

  function workbookRelsXml() {
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>`;
  }

  function corePropsXml(dateKey) {
    const now = new Date().toISOString();
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>Đơn hàng ${escapeXml(dateKey)}</dc:title>
  <dc:creator>Ngoan, Le Van</dc:creator>
  <cp:lastModifiedBy>Ngoan, Le Van</cp:lastModifiedBy>
  <dcterms:created xsi:type="dcterms:W3CDTF">${now}</dcterms:created>
  <dcterms:modified xsi:type="dcterms:W3CDTF">${now}</dcterms:modified>
</cp:coreProperties>`;
  }

  function appPropsXml() {
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
  <Application>Ghi nhận đơn hàng</Application>
  <DocSecurity>0</DocSecurity>
  <ScaleCrop>false</ScaleCrop>
  <HeadingPairs><vt:vector size="2" baseType="variant"><vt:variant><vt:lpstr>Worksheets</vt:lpstr></vt:variant><vt:variant><vt:i4>1</vt:i4></vt:variant></vt:vector></HeadingPairs>
  <TitlesOfParts><vt:vector size="1" baseType="lpstr"><vt:lpstr>Đơn hàng</vt:lpstr></vt:vector></TitlesOfParts>
  <Company></Company>
  <LinksUpToDate>false</LinksUpToDate>
  <SharedDoc>false</SharedDoc>
  <HyperlinksChanged>false</HyperlinksChanged>
  <AppVersion>1.0</AppVersion>
</Properties>`;
  }

  function createZip(files) {
    const localParts = [];
    const centralParts = [];
    let offset = 0;
    const { time, date } = dosDateTime(new Date());

    for (const file of files) {
      const nameBytes = encoder.encode(file.name);
      const dataBytes = typeof file.data === "string" ? encoder.encode(file.data) : file.data;
      const crc = crc32(dataBytes);

      const localHeader = concatBytes(
        uint32(0x04034b50),
        uint16(20),
        uint16(0x0800),
        uint16(0),
        uint16(time),
        uint16(date),
        uint32(crc),
        uint32(dataBytes.length),
        uint32(dataBytes.length),
        uint16(nameBytes.length),
        uint16(0),
        nameBytes
      );

      localParts.push(localHeader, dataBytes);

      const centralHeader = concatBytes(
        uint32(0x02014b50),
        uint16(20),
        uint16(20),
        uint16(0x0800),
        uint16(0),
        uint16(time),
        uint16(date),
        uint32(crc),
        uint32(dataBytes.length),
        uint32(dataBytes.length),
        uint16(nameBytes.length),
        uint16(0),
        uint16(0),
        uint16(0),
        uint16(0),
        uint32(0),
        uint32(offset),
        nameBytes
      );

      centralParts.push(centralHeader);
      offset += localHeader.length + dataBytes.length;
    }

    const centralDirectory = concatBytes(...centralParts);
    const localDirectory = concatBytes(...localParts);
    const endRecord = concatBytes(
      uint32(0x06054b50),
      uint16(0),
      uint16(0),
      uint16(files.length),
      uint16(files.length),
      uint32(centralDirectory.length),
      uint32(localDirectory.length),
      uint16(0)
    );

    return concatBytes(localDirectory, centralDirectory, endRecord);
  }

  function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) {
      crc ^= byte;
      for (let i = 0; i < 8; i += 1) {
        crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
      }
    }
    return (crc ^ 0xffffffff) >>> 0;
  }

  function uint16(value) {
    return Uint8Array.of(value & 0xff, (value >>> 8) & 0xff);
  }

  function uint32(value) {
    return Uint8Array.of(
      value & 0xff,
      (value >>> 8) & 0xff,
      (value >>> 16) & 0xff,
      (value >>> 24) & 0xff
    );
  }

  function concatBytes(...arrays) {
    const total = arrays.reduce((sum, array) => sum + array.length, 0);
    const output = new Uint8Array(total);
    let cursor = 0;
    for (const array of arrays) {
      output.set(array, cursor);
      cursor += array.length;
    }
    return output;
  }

  function dosDateTime(dateValue) {
    const year = Math.max(1980, dateValue.getFullYear());
    const date = ((year - 1980) << 9) | ((dateValue.getMonth() + 1) << 5) | dateValue.getDate();
    const time = (dateValue.getHours() << 11) | (dateValue.getMinutes() << 5) | Math.floor(dateValue.getSeconds() / 2);
    return { date, time };
  }

  function bytesToBase64(bytes) {
    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let output = "";
    for (let i = 0; i < bytes.length; i += 3) {
      const a = bytes[i];
      const hasB = i + 1 < bytes.length;
      const hasC = i + 2 < bytes.length;
      const b = hasB ? bytes[i + 1] : 0;
      const c = hasC ? bytes[i + 2] : 0;
      const triple = (a << 16) | (b << 8) | c;
      output += alphabet[(triple >>> 18) & 63];
      output += alphabet[(triple >>> 12) & 63];
      output += hasB ? alphabet[(triple >>> 6) & 63] : "=";
      output += hasC ? alphabet[triple & 63] : "=";
    }
    return output;
  }

  function columnName(index) {
    let result = "";
    let current = index;
    while (current > 0) {
      current -= 1;
      result = String.fromCharCode(65 + (current % 26)) + result;
      current = Math.floor(current / 26);
    }
    return result;
  }

  function escapeXml(value) {
    return String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&apos;");
  }

  function formatVietnamDateTime(value) {
    if (!value || Number.isNaN(new Date(value).getTime())) return "";
    const parts = new Intl.DateTimeFormat("en-GB", {
      timeZone: "Asia/Ho_Chi_Minh",
      year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
    }).formatToParts(new Date(value));
    const m = Object.fromEntries(parts.map((p) => [p.type, p.value]));
    return `${m.hour}:${m.minute}:${m.second} ${m.day}-${m.month}-${m.year}`;
  }

  globalThis.XlsxBuilder = Object.freeze({ buildOrdersWorkbookBase64 });
})();
