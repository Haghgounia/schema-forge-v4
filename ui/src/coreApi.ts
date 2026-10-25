import { generationUploadError } from './uploadLimits.ts';
import { fetchDownload, fetchJson, params, uploadForm, requireSelectedPlatform, getActiveMetadataConnection } from './transport.ts';

export const PLATFORMS = [
  ['oracle', 'Oracle'], ['postgresql', 'PostgreSQL'], ['db2zos', 'DB2 z/OS'],
  ['db2luw', 'DB2 LUW'], ['sqlserver', 'SQL Server'], ['mysql', 'MySQL'], ['mariadb', 'MariaDB'],
] as const;

export type GenerationSource = 'word' | 'legacy-word' | 'legacy-word/zip' | 'zip' | 'ea-xml';
export type AuditFields = 'source' | 'created-updated' | 'created-last-modified' | 'none';
export type TimestampSemantics = 'source' | 'without-time-zone' | 'with-time-zone' | 'with-local-time-zone';
export type GenerationInput = {
  source: GenerationSource;
  file: File;
  platforms: string[];
  auditFields: AuditFields;
  timestampSemantics: TimestampSemantics;
  schema?: string;
  primaryKeyAsIdentity?: boolean;
};
function platformsQuery(platforms: string[]): URLSearchParams {
  const query = new URLSearchParams();
  for (const platform of platforms) query.append('platform', platform);
  return query;
}
/** Parse a pasted comma/newline/semicolon separated table cohort; preserve first spelling. */
export function parseSelectedTableNames(raw: string): string[] {
  const seen = new Set<string>();
  return raw.split(/[\r\n,;]+/).map(value => value.trim()).filter(value => {
    if (!value) return false;
    const key = value; // Preserve case-distinct requests; the server rejects ambiguous collisions.
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export const coreApi = {
  generate: (input: GenerationInput) => {
    const sizeError = generationUploadError(input.file.size);
    if (sizeError) throw new Error(sizeError);
    requireSelectedPlatform(input.platforms);
    const query = platformsQuery(input.platforms);
    query.set('auditFields', input.auditFields);
    query.set('timestampSemantics', input.timestampSemantics);
    if (input.schema?.trim()) query.set('schema', input.schema.trim());
    if (input.source === 'ea-xml') query.set('primaryKeyAsIdentity', String(Boolean(input.primaryKeyAsIdentity)));
    return fetchDownload(`/api/v1/generate/${input.source}?${query}`,
      `schemaforge-${input.source.replaceAll('/', '-')}.zip`, { method: 'POST', body: uploadForm(input.file) });
  },
  conformance: (input: { scope: 'table' | 'schema'; schema: string; table?: string; platforms: string[] }) => {
    requireSelectedPlatform(input.platforms);
    const query = platformsQuery(input.platforms);
    query.set('schema', input.schema.trim());
    if (input.scope === 'table' && input.table) query.set('table', input.table.trim());
    return fetchJson<ConformanceResponse>(`/api/v1/conformance/${input.scope}?${query}`);
  },
  /** Safe selected-audit API: never silently uses startup JDBC config. */
  selectedAuditPlan: () => {
    if (!getActiveMetadataConnection()?.token) throw new Error('ابتدا یک اتصال JDBC را در بخش اتصالات فعال کنید.');
    return fetchJson<SelectedAuditPlan>('/api/v1/conformance/selected/plan');
  },
  selectedConformance: (input: {scope: 'table' | 'tables' | 'schema'; schema: string;
    table?: string; tables?: string[]}) => {
    if (!getActiveMetadataConnection()?.token) throw new Error('اتصال فعال برای ممیزی الزامی است.');
    if (!input.schema.trim() || (input.scope === 'table' && !input.table?.trim())) {
      throw new Error('Schema و در حالت Table نام جدول الزامی است.');
    }
    if (input.scope === 'tables') {
      const tables = input.tables ?? [];
      if (!tables.length || tables.length > 100 || tables.some(table => !table.trim() || table.length > 128)) {
        throw new Error('برای ممیزی گروهی بین ۱ تا ۱۰۰ نام Table معتبر الزامی است.');
      }
      return fetchJson<SelectedAuditResult>('/api/v1/conformance/selected/tables',
        {method: 'POST', json: {schema: input.schema.trim(), tables}});
    }
    const query = params({schema: input.schema.trim(), table: input.scope === 'table' ? input.table?.trim() : undefined});
    return fetchJson<SelectedAuditResult>(`/api/v1/conformance/selected/${input.scope}?${query}`);
  },
  crud: (platform: 'oracle' | 'sqlserver', schema: string, table: string) => {
    if (!getActiveMetadataConnection()?.token) throw new Error('ابتدا اتصال مقصد را انتخاب کنید.');
    requireSelectedPlatform([platform]);
    return fetchDownload(`/api/v1/generate/${platform}/crud/selected`, `schemaforge-${platform}-crud.sql`,
      { method: 'POST', json: { schema: schema.trim(), table: table.trim() } });
  },
  liveDiagram: (input: {schema: string; table?: string; type: DiagramInput['type'];
    includeColumns: boolean; includeDataTypes: boolean; includePrimaryKeys: boolean;
    includeForeignKeys: boolean}) => {
    if (!getActiveMetadataConnection()?.token) throw new Error('ابتدا اتصال دیتابیس مقصد را انتخاب کنید.');
    if (!input.schema.trim()) throw new Error('Schema مقصد برای نمودار زنده الزامی است.');
    const query = params({schema:input.schema.trim(),table:input.table?.trim(),type:input.type,
      includeColumns:input.includeColumns,includeDataTypes:input.includeDataTypes,
      includePrimaryKeys:input.includePrimaryKeys,includeForeignKeys:input.includeForeignKeys});
    return fetchDownload(`/api/v1/diagram/mermaid/selected?${query}`,
      'schemaforge-live-diagram.mmd');
  },
  mermaid: (input: DiagramInput) => {
    const query = params({
      type: input.type, scope: input.scope, schema: input.schema, root: input.root, selected: input.selected,
      depth: input.depth, includeColumns: input.includeColumns, includeDataTypes: input.includeDataTypes,
      includePrimaryKeys: input.includePrimaryKeys, includeForeignKeys: input.includeForeignKeys,
      includeLogicalForeignKeys: input.includeLogicalForeignKeys,
    });
    return fetchDownload(`/api/v1/diagram/mermaid/canonical-json?${query}`,
      'schemaforge-diagram.mmd', { method: 'POST', body: uploadForm(input.file) });
  },
};
export type DiagramInput = {
  file: File;
  type: 'er' | 'dependency' | 'conceptual-erd';
  scope: 'all' | 'schema' | 'table' | 'table-with-dependencies' | 'selected-tables';
  schema?: string; root?: string; selected?: string;
  depth: number;
  includeColumns: boolean; includeDataTypes: boolean; includePrimaryKeys: boolean;
  includeForeignKeys: boolean; includeLogicalForeignKeys: boolean;
};
export type ConformanceSummary = {
  tablesScanned: number; columnsScanned: number; errorCount: number; warningCount: number;
  infoCount: number; findingCount: number; compliant: boolean;
};
export type ConformanceFinding = { ruleFamily: string; severity: string; code: string; path: string; message: string };
export type ConformanceReport = {
  reportContract: string; platform: string; scope: string; schema: string; table: string | null;
  ruleFamilies: string[];
  ruleFamilySummaries?: {ruleFamily: string; errorCount: number; warningCount: number; infoCount: number; findingCount: number}[];
  summary: ConformanceSummary; findings: ConformanceFinding[];
};
export type ConformanceResponse = ConformanceReport | { reportContract: string; reports: ConformanceReport[]; platforms: string[] };
export type SelectedAuditTarget = {
  platform: string; jdbcTarget: string; databaseName: string; username: string;
  databaseProduct: string; databaseVersion: string; verifiedSchema: string;
  defaultSchema: string; expiresAt: string;
};
export type AuditRule = {id: string; description: string};
export type SelectedAuditPlan = {
  reportContract: string; target: SelectedAuditTarget; ruleFamilies: AuditRule[];
  readOnly: boolean; notIncluded: string[];
};
export type SelectedAuditResult = {
  reportContract: string; target: SelectedAuditTarget; auditedAt: string;
  report: ConformanceReport; requestedTables?: string[];
};
