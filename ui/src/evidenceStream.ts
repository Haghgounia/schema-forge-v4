/**
 * Bounded, streaming RFC-4180 CSV scan for one Glossary term.
 * Unlike a report preview, evidence needs the full occurrence count. We never
 * materialize the full CSV, never approximate the count, and retain at most 25
 * matched rows. All data originates in the already-downloaded ZIP.
 */
export const MAX_EVIDENCE_SCAN_BYTES = 128 * 1024 * 1024;
export const MAX_EVIDENCE_ROWS_SHOWN = 25;
const MAX_CSV_FIELD_LENGTH = 16_384;
const MAX_CSV_ROW_LENGTH = 65_536;
const MAX_CSV_FIELDS = 64;

export type EvidenceRow = {
  source: string;
  objectType: string;
  table: string;
  column: string;
  identifier: string;
  status: string;
};
export type EvidenceResult = { total: number; rows: EvidenceRow[] };
type CsvMode = 'start' | 'plain' | 'quoted' | 'after-quote';

export class TermEvidenceStreamReader {
  private readonly decoder = new TextDecoder('utf-8', { fatal: true });
  private readonly query: string;
  private readonly rows: EvidenceRow[] = [];
  private readonly header = new Map<string, number>();
  private row: string[] = [];
  private field = '';
  private mode: CsvMode = 'start';
  private skipLf = false;
  private lineLength = 0;
  private headerSeen = false;
  private total = 0;
  private final = false;

  constructor(term: string) {
    this.query = term.toUpperCase();
  }

  /** A chunk may split UTF-8 sequences, quoted commas, CRLF or escaped quotes. */
  writeBytes(bytes: Uint8Array): void {
    if (this.final) throw new Error('پردازش شواهد قبلاً پایان یافته است.');
    this.writeText(this.decoder.decode(bytes, { stream: true }));
  }

  private addField(): void {
    if (this.row.length >= MAX_CSV_FIELDS) throw new Error('تعداد ستون‌های CSV شواهد از حد ایمن بیشتر است.');
    this.row.push(this.field);
    this.field = '';
    this.mode = 'start';
  }
  private addChar(char: string): void {
    this.field += char;
    if (this.field.length > MAX_CSV_FIELD_LENGTH) throw new Error('یک فیلد CSV شواهد بیش از حد طولانی است.');
  }
  private finishRecord(): void {
    this.addField();
    const record = this.row;
    this.row = [];
    this.lineLength = 0;
    if (record.length === 1 && record[0] === '') return;
    if (!this.headerSeen) {
      record.forEach((name, index) => this.header.set(name.replace(/^\ufeff/, '').trim().toLowerCase(), index));
      if (!this.header.has('unknown_term')) throw new Error('ستون unknown_term در گزارش شواهد وجود ندارد.');
      this.headerSeen = true;
      return;
    }
    const at = (name: string) => record[this.header.get(name) ?? -1] ?? '';
    if (at('unknown_term').toUpperCase() !== this.query) return;
    this.total++;
    if (this.rows.length < MAX_EVIDENCE_ROWS_SHOWN) this.rows.push({
      source: at('source_name'), objectType: at('object_type'),
      table: at('table_name'), column: at('column_name'),
      identifier: at('identifier'), status: at('review_status'),
    });
  }
  private writeText(text: string): void {
    for (let i = 0; i < text.length; i++) {
      const char = text[i];
      if (this.skipLf) {
        this.skipLf = false;
        if (char === '\n') continue;
      }
      this.lineLength++;
      if (this.lineLength > MAX_CSV_ROW_LENGTH) throw new Error('طول یکی از رکوردهای CSV شواهد از حد ایمن بیشتر است.');
      if (this.mode === 'quoted') {
        if (char === '"') this.mode = 'after-quote';
        else this.addChar(char);
      } else if (this.mode === 'after-quote') {
        if (char === '"') { this.addChar('"'); this.mode = 'quoted'; }
        else if (char === ',') this.addField();
        else if (char === '\r' || char === '\n') {
          this.finishRecord();
          if (char === '\r') this.skipLf = true;
        } else if (char !== ' ' && char !== '\t') {
          throw new Error('قالب نقل‌قول در CSV شواهد نامعتبر است.');
        }
      } else if (char === ',' || char === '\r' || char === '\n') {
        if (char === ',') this.addField();
        else {
          this.finishRecord();
          if (char === '\r') this.skipLf = true;
        }
      } else if (char === '"' && this.mode === 'start') {
        this.mode = 'quoted';
      } else {
        if (char === '"') throw new Error('قالب نقل‌قول در CSV شواهد نامعتبر است.');
        this.mode = 'plain';
        this.addChar(char);
      }
    }
  }
  finish(): EvidenceResult {
    if (this.final) throw new Error('پردازش شواهد قبلاً پایان یافته است.');
    this.writeText(this.decoder.decode());
    this.final = true;
    if (this.mode === 'quoted') throw new Error('رکورد CSV شواهد دارای نقل‌قول بسته‌نشده است.');
    if (this.row.length > 0 || this.field.length > 0 || this.mode === 'after-quote') this.finishRecord();
    if (!this.headerSeen) throw new Error('CSV شواهد فاقد سطر عنوان معتبر است.');
    return { total: this.total, rows: this.rows };
  }
}
