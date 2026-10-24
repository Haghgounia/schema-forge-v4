import { generationUploadError } from './uploadLimits.ts';
import { fetchDownload, fetchJson, params, uploadForm, requireSelectedPlatform } from './transport.ts';

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
  crud: (platform: 'oracle' | 'sqlserver', schema: string, table: string) => {
    requireSelectedPlatform([platform]);
    return fetchDownload(`/api/v1/generate/${platform}/crud`, `schemaforge-${platform}-crud.sql`,
      { method: 'POST', json: { schema: schema.trim(), table: table.trim() } });
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
  ruleFamilies: string[]; summary: ConformanceSummary; findings: ConformanceFinding[];
};
export type ConformanceResponse = ConformanceReport | { reportContract: string; reports: ConformanceReport[]; platforms: string[] };
