/** Small valid 4-page PDF, used only to exercise the actual PDF.js worker and canvas. */
export function pdfFixture() {
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Count 4 /Kids [3 0 R 4 0 R 5 0 R 6 0 R] >>']
  for (let i = 0; i < 4; i++) objects.push('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 500] /Resources << /Font << /F1 7 0 R >> >> /Contents 8 0 R >>')
  objects.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
  const stream = 'BT /F1 18 Tf 30 450 Td (Research paper fixture) Tj ET'
  objects.push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`)
  let text = '%PDF-1.7\n'; const offsets = [0]
  objects.forEach((object, index) => { offsets.push(Buffer.byteLength(text)); text += `${index + 1} 0 obj\n${object}\nendobj\n` })
  const xref = Buffer.byteLength(text)
  text += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  for (const offset of offsets.slice(1)) text += `${String(offset).padStart(10, '0')} 00000 n \n`
  return Buffer.from(`${text}trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`)
}
